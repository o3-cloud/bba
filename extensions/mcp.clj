(ns bba.extensions.mcp
  "Model Context Protocol (MCP) servers as bba tools.

  A core extension: it always loads, and adds no tools until an mcp.json
  names servers. It launches the commands you configure, so point it only at trusted servers.

  Config: ~/.bba/mcp.json (always) and <cwd>/.bba/mcp.json (only when trusted:
  BBA_MCP_TRUST=1 or a .bba/mcp.trusted file in the project). The file is JSON, and
  both shapes work:

    keys are strings: mcpServers -> server name -> command and args.
    Example: npx -y @modelcontextprotocol/server-everything stdio

  Each MCP tool becomes mcp__<server>__<tool>, with the MCP inputSchema passed
  through as the bba input-schema. Servers start with the session, stop after ten
  idle minutes and restart on the next call; /mcp shows what is wired up. stdio
  transport only.

  Both protocol eras are spoken: modern (server/discover, per-request _meta,
  2026-07-28 and later) and legacy (the initialize handshake, 2025-11-25 and
  earlier). Replies are matched by JSON-RPC id, so interleaved notifications
  (notifications/tools/list_changed and others) are skipped rather than mistaken
  for the answer to a request."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bba.ext :as ext]))

(def ^:private legacy-version "2025-06-18")
(def ^:private modern-version "2026-07-28")
(def ^:private idle-ms 600000)
(def ^:private max-name 64)
(def ^:private handshake-ms 30000)
(def ^:private call-ms 180000)
(def ^:private err-lines 20)

(defn- now [] (System/currentTimeMillis))

;; ------------------------------------------------------------------- config

(defn- read-json [path]
  (when (fs/exists? path)
    (try (json/parse-string (slurp (str path)))
         (catch Exception e
           (ext/warn "mcp: cannot read " path ": " (ex-message e))
           nil))))

(defn- servers-in [cfg]
  (let [c (or cfg {})]
    (if (map? c) (or (get c "mcpServers") c) {})))

(defn trusted?
  "Project config names commands to run, so it needs trust. User config does not."
  [ctx]
  (or (= "1" (get (System/getenv) "BBA_MCP_TRUST"))
      (fs/exists? (fs/path (:cwd ctx) ".bba" "mcp.trusted"))))

(defn server-configs
  "Merged server config, project over user. Without trust the project file is ignored."
  [ctx]
  (let [user (servers-in (read-json (fs/path (:home ctx) "mcp.json")))
        proj (when (trusted? ctx)
               (servers-in (read-json (fs/path (:cwd ctx) ".bba" "mcp.json"))))]
    (merge user proj)))

;; -------------------------------------------------------------------- names

(defn- slug [s] (str/replace (str s) #"[^A-Za-z0-9_-]" "_"))
(defn- clip-name [n] (subs n 0 (min max-name (count n))))

(defn tool-name
  "mcp__<server>__<tool>, clipped to 64 chars, the limit both MCP and model APIs set."
  [server tool]
  (clip-name (str "mcp__" (slug server) "__" (slug tool))))

;; ---------------------------------------------------------------- transport

(defn- spawn [command args cwd]
  (let [pb (ProcessBuilder. (into-array String (cons command (vec (or args [])))))]
    (when (and cwd (not (str/blank? (str cwd))))
      (.directory pb (java.io.File. (str cwd))))
    (let [p (.start pb)]
      {:proc p
       :in (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream p) "UTF-8"))
       :out (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream p) "UTF-8"))
       :err (java.io.BufferedReader. (java.io.InputStreamReader. (.getErrorStream p) "UTF-8"))
       :pending (atom {})
       :next-id (atom 0)
       :stderr (atom [])
       :used (atom (now))
       :era (atom nil)})))

(defn- spawn-thread! [f]
  (doto (Thread. ^Runnable f) (.setDaemon true) (.start)))

(defn- drain-stderr!
  "An unread stderr pipe fills and hangs the server, so always drain it."
  [srv]
  (spawn-thread!
   (fn []
     (try (loop []
            (when-let [l (.readLine ^java.io.BufferedReader (:err srv))]
              (swap! (:stderr srv) (fn [v] (vec (take-last err-lines (conj v l)))))
              (recur)))
          (catch Exception _)))))

(defn- read-loop!
  "Demultiplex by JSON-RPC id. A message without an id is a notification: dropped."
  [srv]
  (spawn-thread!
   (fn []
     (try
       (loop []
         (when-let [line (.readLine ^java.io.BufferedReader (:out srv))]
           (when-not (str/blank? line)
             (let [m (try (json/parse-string line true) (catch Exception _ nil))]
               (when (and (map? m) (some? (:id m)))
                 (when-let [p (get @(:pending srv) (:id m))]
                   (deliver p m)
                   (swap! (:pending srv) dissoc (:id m))))))
           (recur)))
       (catch Exception _))
     (doseq [[_ p] @(:pending srv)]
       (deliver p {:error {:message "server closed the connection"}})))))

(defn- send! [srv msg]
  (let [w ^java.io.BufferedWriter (:in srv)]
    (.write w (json/generate-string msg))
    (.newLine w)
    (.flush w)))

(defn- modern-meta []
  {:_meta {"io.modelcontextprotocol/protocolVersion" modern-version
           "io.modelcontextprotocol/clientInfo" {"name" "bba" "version" "0.1.0"}
           "io.modelcontextprotocol/clientCapabilities" {}}})

(defn rpc
  "One JSON-RPC request. Returns the reply, or {:error {:message s}} on timeout or
  transport failure. Modern servers get _meta on every request."
  [srv method params ms]
  (let [id (swap! (:next-id srv) inc)
        p (promise)
        params (merge (if (= :modern @(:era srv)) (modern-meta) {}) (or params {}))]
    (swap! (:pending srv) assoc id p)
    (try
      (send! srv {:jsonrpc "2.0" :id id :method method :params params})
      (let [r (deref p ms ::timeout)]
        (when (= ::timeout r) (swap! (:pending srv) dissoc id))
        (if (= ::timeout r)
          {:error {:message (str method " timed out after " ms " ms")}}
          r))
      (catch Exception e
        (swap! (:pending srv) dissoc id)
        {:error {:message (str method ": " (ex-message e))}}))))

(defn- handshake!
  "Modern server/discover first, then the legacy initialize handshake."
  [srv]
  (let [d (rpc srv "server/discover" {} handshake-ms)]
    (if (get-in d [:result :capabilities])
      (do (reset! (:era srv) :modern)
          {:caps (get-in d [:result :capabilities])
           :info (get-in d [:result :_meta "io.modelcontextprotocol/serverInfo"])})
      (let [i (rpc srv "initialize" {:protocolVersion legacy-version
                                      :capabilities {}
                                      :clientInfo {"name" "bba" "version" "0.1.0"}}
                   handshake-ms)]
        (if-let [res (:result i)]
          (do (reset! (:era srv) :legacy)
              (send! srv {:jsonrpc "2.0" :method "notifications/initialized"})
              {:caps (:capabilities res) :info (:serverInfo res)})
          {:error (or (get-in d [:error :message])
                      (get-in i [:error :message])
                      "no reply to server/discover or initialize")})))))

(defn- list-tools
  "Every page of tools/list, following nextCursor."
  [srv]
  (loop [cursor nil acc []]
    (let [r (rpc srv "tools/list" (when cursor {:cursor cursor}) handshake-ms)]
      (if-let [err (:error r)]
        (if (seq acc) acc {:error err})
        (let [acc (into acc (:tools (:result r)))
              nx (:nextCursor (:result r))]
          (if nx (recur nx acc) acc))))))

;; ------------------------------------------------------------------ results

(defn- flatten-content
  "MCP content is an array of blocks; a bba tool returns one string."
  [content]
  (if (string? content)
    content
    (str/join "\n"
              (keep (fn [b]
                      (case (:type b)
                        "text" (:text b)
                        "image" (str "[image " (:mimeType b) "]")
                        "audio" (str "[audio " (:mimeType b) "]")
                        "resource" (let [r (:resource b)]
                                      (str "[" (:uri r) "]\n"
                                           (or (:text r) (str "<" (or (:mimeType r) "binary") ">"))))
                        (str "<" (:type b) ">")))
                    content))))

;; --------------------------------------------------------------- live state

(defonce state
  (atom {:servers {}     ; name -> live entry   {:srv :caps :info :tool-defs}
         :status {}      ; name -> line for /mcp
         :config {}}))   ; name -> config, so a call can restart a stopped server

(defn- alive? [srv]
  (try (.isAlive ^Process (:proc srv)) (catch Exception _ false)))

(defn- kill! [srv]
  (when (and srv (:proc srv))
    (let [p ^Process (:proc srv)]
      (try (.close ^java.io.BufferedWriter (:in srv)) (catch Exception _))
      (try (.destroy p) (catch Exception _))
      (try (when-not (.waitFor p 2000) (.destroyForcibly p)) (catch Exception _)))))

(defn stop-all!
  "Destroy every child process this extension started."
  []
  (doseq [[_ v] (:servers @state)] (kill! (:srv v)))
  (swap! state assoc :servers {} :status {}))

(defn- prune-idle!
  "Stop servers idle for longer than idle-ms. Called on each tool call, so MCP
  servers hold no resources when they are not in use."
  []
  (let [t (now)]
    (doseq [[n v] (:servers @state)]
      (when (and (:srv v) (alive? (:srv v)) (> (- t @(:used (:srv v))) idle-ms))
        (kill! (:srv v))
        (swap! state assoc-in [:servers n :srv] nil)
        (swap! state assoc-in [:status n] "idle; restarts on the next call")))))

(defn- start!
  "Spawn, handshake and list tools. Returns the live entry, or {:error s}."
  [cfg]
  (let [command (or (:command cfg) (get cfg "command"))
        args (or (:args cfg) (get cfg "args"))
        cwd (or (:cwd cfg) (get cfg "cwd"))]
    (if-not (and command (string? command) (not (str/blank? command)))
      {:error "config needs a command string"}
      (let [srv (try (spawn command args cwd)
                     (catch Exception e
                       {:error (str "cannot start " command ": " (ex-message e))}))]
        (if (:error srv)
          srv
          (try
            (drain-stderr! srv)
            (read-loop! srv)
            (let [hs (handshake! srv)]
              (if-let [err (:error hs)]
                (do (kill! srv) {:error err})
                (let [tools (list-tools srv)]
                  (if-let [err (:error tools)]
                    (do (kill! srv) {:error err})
                    {:srv srv :caps (:caps hs) :info (:info hs) :tool-defs tools}))))
            (catch Exception e
              (kill! srv)
              {:error (ex-message e)})))))))

(defn- ensure-server!
  "The live entry for a server, starting it unless it is already running."
  [sname cfg]
  (let [v (get-in @state [:servers sname])]
    (if (and (:srv v) (alive? (:srv v)))
      (do (reset! (:used (:srv v)) (now)) v)
      (let [r (start! cfg)]
        (swap! state assoc-in [:servers sname] r)
        (swap! state assoc-in [:status sname]
               (if (:error r)
                 (str "failed: " (:error r))
                 (str "ok, " (count (:tool-defs r)) " tools")))
        r))))

(defn- call!
  "tools/call on a live server, retried once, as MCP clients are expected to."
  [srv tool args]
  (loop [attempt 1]
    (let [r (rpc srv "tools/call" {:name tool :arguments (or args {})} call-ms)]
      (cond
        (get-in r [:result :content])
        (cond-> {:content (flatten-content (get-in r [:result :content]))}
          (get-in r [:result :isError]) (assoc :is-error true))

        (and (:error r) (< attempt 2))
        (do (Thread/sleep 150) (recur (inc attempt)))

        :else
        {:content (str "MCP error: " (or (get-in r [:error :message]) "no result"))
         :is-error true}))))

;; ------------------------------------------------------------------- wiring

(defn- tool-handler
  "A bba tool handler for one MCP tool. Restarts the server when it has stopped."
  [sname tool]
  (fn [args _ctx]
    (prune-idle!)
    (let [v (ensure-server! sname (get-in @state [:config sname]))]
      (if-let [err (:error v)]
        {:content (str "MCP server " sname " is not running: " err) :is-error true}
        (call! (:srv v) tool args)))))

(defn- register-all!
  "Start every configured server and register its tools. Re-registering a name
  replaces that entry, and only names this extension owns are touched."
  [ctx]
  (let [cfgs (server-configs ctx)]
    (swap! state assoc :config cfgs)
    (doseq [[sname cfg] cfgs]
      (let [v (ensure-server! sname cfg)]
        (if-let [err (:error v)]
          (ext/warn "mcp: server " sname " failed: " err)
          (do (doseq [t (:tool-defs v)]
                (ext/register-tool!
                 {:name (tool-name sname (:name t))
                  :description (str "MCP " sname " - "
                                    (or (:description t) (:title t) (:name t)))
                  :input-schema (or (:inputSchema t) {:type "object" :properties {}})
                  :handler (tool-handler sname (:name t))}))
              (ext/warn (str "mcp: " sname ": " (count (:tool-defs v)) " tools active"))))))))

(defn- status-lines [ctx]
  (let [{:keys [config status]} @state]
    (if (empty? config)
      [(str "mcp: no servers configured. Add an mcpServers block to "
            (fs/path (:home ctx) "mcp.json")
            (when-not (trusted? ctx) ", or trust this project"))]
      (for [[sname _] (sort config)]
        (str "  " sname " - " (get status sname "not started"))))))

(ext/on! :session-start
        (fn [_ ctx]
          (try (register-all! ctx)
               (catch Exception e (ext/warn "mcp: setup failed: " (ex-message e))))))

;; On /reload the kernel drops extension registrations. The fresh load defines a
;; new stop-all! and calls it here, which first runs the unloaders of the old load.
(when-let [f (resolve 'bba.ext/on-unload!)]
  (f stop-all!))

(ext/register-command! "mcp"
  (fn [args ctx]
    (case (str/trim (str args))
      "stop" (do (stop-all!) (println "mcp: stopped every server"))
      (do (println "MCP servers:")
          (doseq [l (status-lines ctx)] (println l))
          (println "  usage: /mcp | /mcp stop")))))
