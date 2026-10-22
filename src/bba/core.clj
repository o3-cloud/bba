(ns bba.core
  "System prompt, agent loop, tool dispatch, extension loader, sessions."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bba.ext :as ext]
            [bba.provider :as provider]
            [bba.toolview :as toolview]))

(def default-max-turns 200)

;; ---------------------------------------------------------------- prompt

(defn- agents-md [dir]
  (let [f (fs/path dir "AGENTS.md")]
    (when (fs/exists? f) (str "\n\n# " f "\n\n" (slurp (str f))))))

(defn system-prompt [{:keys [cwd home]}]
  (str "You are bba, a small coding agent running in a terminal.\n"
       "Working folder: " cwd "\n"
       "Tools: " (str/join ", " (sort (map :name (ext/tools)))) ".\n"
       "Use the tools to read, change and run things. Be brief.\n"
       "You can extend yourself: write a Clojure file to .bba/extensions/<name>.clj, then ask the user "
       "to type /reload. This is the whole extension API (do not search the disk for bba source):\n"
       "(ns my-ext (:require [bba.ext :as ext] [clojure.string :as str]))\n"
       "(ext/register-tool! {:name \"shout\" :description \"Upper-case text.\"\n"
       "  :input-schema {:type \"object\" :properties {:text {:type \"string\"}} :required [\"text\"]}\n"
       "  :handler (fn [{:keys [text]} ctx] (str/upper-case text))})  ; return a string or {:content s :is-error true}\n"
       "(ext/register-command! \"hi\" (fn [args ctx] (println \"hi\" args)))  ; the user types /hi\n"
       "(ext/on! :tool-call (fn [{:keys [name input]} ctx] nil))  ; return {:block true :reason \"..\"} to block\n"
       "(ext/on! :prompt-footer (fn [_ ctx] {:order 10 :text \"..\"}))  ; a dim row under the input\n"
       "Handlers get input keys as keywords. ctx has :cwd (the working folder) and :env (environment map)."
       (agents-md home)
       (agents-md cwd)))

(defn prompt-for
  "The system prompt: a layer may replace the base text (:system-prompt); AGENTS.md is always added."
  [{:keys [home cwd] :as ctx}]
  (if-let [f (:system-prompt ctx)] (str (f ctx) (agents-md home) (agents-md cwd)) (system-prompt ctx)))

(defn tool-specs []
  (->> (ext/tools)
       (sort-by :name)
       (mapv (fn [{:keys [name description input-schema]}]
               {:name name :description description :input_schema input-schema}))))

;; ---------------------------------------------------------------- sessions

(defn new-session-file [cwd]
  (let [ts (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.))
        f (fs/path cwd ".bba" "sessions" (format "%s-%04d.jsonl" ts (rand-int 10000)))]
    (fs/create-dirs (fs/parent f))
    (let [gi (fs/path (fs/parent f) ".gitignore")] ; sessions hold tool output; keep them out of git
      (when-not (fs/exists? gi) (spit (str gi) "*\n")))
    (str f)))

(defn latest-session [cwd]
  (let [dir (fs/path cwd ".bba" "sessions")]
    (when (fs/directory? dir) (some->> (fs/glob dir "*.jsonl") (map str) sort last))))

(defn- final-answer? [{:keys [role content]}]
  (and (= role "assistant") (not-any? #(= "tool_use" (:type %)) content)))

(defn- user-text? [{:keys [role content]}]
  (and (= role "user") (not-any? #(= "tool_result" (:type %)) content)))

(defn replayable
  "Keep only complete exchanges (user text ... final answer). Drops a turn that was cancelled
  or crashed, so a dangling tool_use never reaches the provider."
  [msgs]
  (loop [[m & more] msgs done [] pending []]
    (cond (nil? m) done
          (user-text? m) (recur more done [m])
          (empty? pending) (recur more done [])
          (final-answer? m) (recur more (into done (conj pending m)) [])
          :else (recur more done (conj pending m)))))

(defn parse-lines
  "Parse session JSONL into messages. A crash can leave the last line half-written, so
  the read stops at the first line that does not parse: a good prefix loads, and a torn
  or corrupt record never becomes a message. Warns with the line number when it stops."
  [text]
  (let [msgs (reduce (fn [acc [i l]]
                       (if (str/blank? l)
                         acc
                         (let [m (try (json/parse-string l true) (catch Exception _ ::bad))]
                           (if (= ::bad m)
                             (reduced (with-meta acc {:stopped-at (inc i)}))
                             (conj acc (select-keys m [:role :content]))))))
                     [] (map-indexed vector (str/split-lines text)))]
    (when-let [n (:stopped-at (meta msgs))]
      (ext/warn "session file is damaged at line " n "; loaded the first " (count msgs) " record(s)"))
    msgs))

(defn load-session [file]
  (replayable (parse-lines (slurp file))))

(defn log-message!
  "Append `msg` to the session file, tagged with the time and the world (ctx :world) it ran in."
  [{:keys [session-file world]} msg]
  (when session-file
    (spit session-file
          (str (json/generate-string (cond-> (assoc (select-keys msg [:role :content]) :ts (System/currentTimeMillis))
                                       world (assoc :world world)))
               "\n")
          :append true)))

(defn session-world
  "The world the last message of a session ran in, or nil (older sessions have none)."
  [file]
  (some->> (str/split-lines (slurp file))
           (remove str/blank?)
           reverse
           (some #(try (:world (json/parse-string % true)) (catch Exception _ nil)))))

;; ---------------------------------------------------------------- tools

(defn- blocked-reason
  "Run :tool-call hooks. First block wins. A hook that throws blocks (fail-closed)."
  [call ctx]
  (some (fn [{:keys [handler source]}]
          (try (let [r (handler call ctx)]
                 (when (:block r) (or (:reason r) (str "blocked by " source))))
               (catch Exception e
                 (ext/warn "tool-call hook in " source " failed: " (ex-message e))
                 (str "hook error in " source ": " (ex-message e)))))
        (ext/hooks :tool-call)))

(defn- missing-keys [tool input]
  (remove #(contains? input (keyword %)) (get-in tool [:input-schema :required])))

(defn run-tool
  "Run one tool_use block. Always returns a tool_result block; never throws."
  [{:keys [id name input]} ctx]
  (let [input (or input {})
        tool (ext/tool name)
        result (try
                 (let [missing (when tool (seq (missing-keys tool input)))
                       reason (when (and tool (not missing)) (blocked-reason {:name name :input input} ctx))]
                   (cond
                     (nil? tool) {:content (str "unknown tool: " name) :is-error true}
                     missing {:content (str "missing input: " (str/join ", " missing)) :is-error true}
                     reason {:content (str "blocked: " reason) :is-error true}
                     :else (let [r ((:handler tool) input ctx)]
                             (if (map? r) r {:content (str r)}))))
                 (catch InterruptedException e (throw e))  ; Ctrl-C: stop the turn, do not report a tool error
                 (catch Exception e {:content (str "tool error: " (ex-message e)) :is-error true}))]
    (cond-> {:type "tool_result" :tool_use_id id :content (str (:content result))}
      (:is-error result) (assoc :is_error true))))

;; ---------------------------------------------------------------- hooks

(def footer-default-order 50)

(def footer-default-row 1)

(defn footer-part
  "One :prompt-footer result as [row order text]: a bare string takes the default row
  and order, and a map says where its text belongs. Row 0 is the top row under the
  input; row 1 is the next row down, which a result with no :row takes, so an
  extension cannot land on the top row by accident. nil when the result has no text."
  [r]
  (let [[row order text] (if (map? r)
                           [(or (:row r) footer-default-row)
                            (or (:order r) footer-default-order)
                            (:text r)]
                           [footer-default-row footer-default-order r])]
    (when (and (string? text) (not (str/blank? text)))
      [(long row) (long order) (str/trim text)])))

(defn prompt-footer
  "The dim status line under the input: what the :prompt-footer hooks return, joined by
  a dot. A hook gets the live ctx, so its part shows the provider and model that the
  next message uses; the order comes from the hook, not from the load order of the
  files. A hook can put its text on a row with :row, so the folder and the world sit
  on the top row and the target and the context on the row below. One row returns a
  string; two or more rows return a vector of strings, which tui draws on its own rows.
  A hook that returns nil adds nothing; a hook that throws is warned and ignored
  (fail-open). nil when no hook answers, so the caller passes no footer."
  [ctx]
  (let [rows (->> (ext/emit-results :prompt-footer {} ctx)
                  (keep footer-part)
                  (sort-by (juxt first second))
                  (partition-by first)
                  (map (fn [ps] (->> ps (map #(nth % 2)) distinct vec)))
                  (remove empty?))]
    (case (count rows)
      0 nil
      1 (str/join " · " (first rows))
      (mapv #(str/join " · " %) (vec rows)))))

(defn apply-request-hooks
  "Let extensions transform the request that goes to the provider. Called once per
  turn, so a hook sees the live ctx (for example a provider switch).
  Each :request hook may return {:request req'} to replace it. A hook that throws
  or returns something that is not a map with a :messages vector is ignored
  (fail-open: the previous request is kept)."
  [req ctx]
  (reduce (fn [r {:keys [handler source]}]
            (try (let [out (handler {:request r} ctx)]
                   (if (and (map? out) (map? (:request out)) (vector? (get-in out [:request :messages])))
                     (:request out)
                     (do (when (some? out) (ext/warn "request hook in " source " returned something that is not {:request req}; ignored"))
                         r)))
                 (catch Exception e
                   (ext/warn "request hook in " source " failed: " (ex-message e))
                   r)))
          req (ext/hooks :request)))

;; ---------------------------------------------------------------- loop

(defn- show
  "Report a tool event {:phase :call|:result :tool name :text rendered} to (:show ctx),
  or print its text with (:print ctx)."
  [ctx ev]
  (if-let [f (:show ctx)] (f ev) (when-let [p (:print ctx)] (p (:text ev)))))

(defn- incomplete-note [why]
  (str "\n\n(stopped: the model returned an incomplete response (" why "): "
       (if (= "max_output_tokens" why)
         "it ran out of room while thinking; try a shorter question or a lower reasoning effort"
         "the server stopped before producing an answer")
       ")"))

(defn prompt-request
  "The request that the next call sends, before the :request hooks. The turn rule
  and /context measure this, so their numbers match what the provider sees."
  [ctx messages]
  (cond-> {:messages messages :system (prompt-for ctx) :tools (tool-specs)}
    (:on-text ctx) (assoc :on-text (:on-text ctx))))

(defn run-turns
  "Call the provider until it stops asking for tools.
  Returns {:status :ok|:incomplete|:max-turns, :text final-text, :messages all-messages,
  :usage the summed {:input :output :cost} the provider reported, or nil,
  :last-usage the same sum for the final reply only (the size of what is sent next)}. A reply the
  provider marked :incomplete returns :status :incomplete, never :ok: a cut answer must
  not look like a finished turn."
  [ctx messages]
  (let [call (or (ext/provider) (provider/from-env (:env ctx)))
        max-turns (or (:max-turns ctx) default-max-turns)
        width (or (get-in ctx [:md :width]) 80)]
    (loop [messages messages turn 0 usage nil last-usage nil]
      (if (>= turn max-turns)
        {:status :max-turns :messages messages :usage usage :last-usage last-usage
         :text (str "stopped: reached max turns (" max-turns ")")}
        (let [_ (when-let [f (:on-request ctx)] (f))
              sent (apply-request-hooks (prompt-request ctx messages) ctx)
              reply (call sent)
              amsg {:role "assistant" :content (:content reply)}
              uses (filter #(= "tool_use" (:type %)) (:content reply))
              messages (conj messages amsg)
              usage (provider/add-usage usage (:usage reply))]
          (log-message! ctx amsg)
          (if (empty? uses)
            (let [text (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) (:content reply)))]
              (if-let [why (:incomplete reply)]
                {:status :incomplete :messages messages :usage usage
                 :last-usage (:usage reply) :incomplete why
                 :text (str text (incomplete-note why))}
                {:status :ok :messages messages :usage usage
                 :last-usage (:usage reply) :text text}))
            (let [results (mapv (fn [u]
                                  (show ctx {:phase :call :tool (:name u) :input (:input u)
                                             :text (toolview/call-str (:name u) (:input u) width)})
                                  (let [r (run-tool u ctx)]
                                    (show ctx {:phase :result :tool (:name u) :input (:input u)
                                               :content (:content r) :error? (boolean (:is_error r))
                                               :text (toolview/result-str (:name u) (:content r) (:is_error r) width)})
                                    r))
                                uses)
                  umsg {:role "user" :content results}]
              (log-message! ctx umsg)
              (recur (conj messages umsg) (inc turn) usage (:usage reply)))))))))

(defn user-message [text] {:role "user" :content [{:type "text" :text text}]})

;; ---------------------------------------------------------------- extensions

(def core-extension-dir
  "extensions/ next to src/: core extensions that ship with bba and always load."
  (str (fs/path (-> (io/resource "bba/core.clj") io/file fs/parent fs/parent fs/parent) "extensions")))

(defn extension-dirs [{:keys [cwd home]}]
  [(str (fs/path home "extensions")) (str (fs/path cwd ".bba" "extensions"))])

(defn load-extension!
  "Load one file. On error: warn with the file name and continue."
  [path]
  (try (binding [ext/*source* path] (load-file path)) true
       (catch Throwable e
         (ext/warn "failed to load " path ": " (ex-message e))
         false)))

(defn load-extensions!
  "Remove extension registrations, then load every *.clj in the core folder and,
  unless `core-only?`, the user and project folders."
  [ctx & [core-only?]]
  (ext/reset-extensions!)
  (doseq [dir (cons core-extension-dir (when-not core-only? (extension-dirs ctx)))
          :when (fs/directory? dir)
          f (sort (map str (fs/glob dir "*.clj")))]
    (load-extension! f)))

;; ---------------------------------------------------------------- source

(def core-source-dir
  "src/, the folder that holds the bba namespaces, found through a loaded file."
  (str (-> (io/resource "bba/core.clj") io/file fs/parent fs/parent)))

(defn- source-ns
  "The namespace that a source file defines, relative to `root`:
  bba/codex_auth.clj gives bba.codex-auth."
  [root f]
  (-> (str (fs/relativize root f))
      (str/replace #"\.clj$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn- source-deps
  "The bba namespaces that one source file requires in its ns form. Analysis needs a
  function to exist, so a namespace must reload after the namespaces that it uses."
  [root f]
  (let [self (source-ns root f)
        head (->> (str/split-lines (slurp f))
                  (take-while #(not (re-find #"^\((def|defn|defonce)" %)))
                  (str/join "\n"))]
    (->> (re-seq #"bba\.[a-z0-9\.\-]+" head)
         (map symbol)
         (remove #(= % self))
         set)))

(defn core-namespace-order
  "The bba namespaces, dependencies first, so a reload cannot analyse a call to a
  function that the running image does not have yet. A namespace comes after every
  namespace of the same folder that it requires."
  [& {:keys [dir]}]
  (let [root (or dir core-source-dir)
        deps (into {}
                   (for [f (sort (map str (fs/glob root "**.clj")))]
                     [(source-ns root f) (source-deps root f)]))
        done (atom #{})
        order (atom [])]
    (letfn [(visit [n]
              (when-not (contains? @done n)
                (swap! done conj n)
                (doseq [d (sort (get deps n))] (when (contains? deps d) (visit d)))
                (swap! order conj n)))]
      (doseq [n (sort (keys deps))] (visit n))
      @order)))

(defn reload-source!
  "Reload the bba namespaces from src/, dependencies first, so that the running image
  gets a function that this build added. Without it, /reload can load an extension
  that calls a new core function only after a restart. A namespace that fails warns
  and does not stop the others. Returns the namespaces that reloaded."
  []
  (reduce (fn [done n]
            (try (require n :reload) (conj done n)
                 (catch Throwable e
                   (ext/warn "failed to reload " n ": " (ex-message e))
                   done)))
          []
          (core-namespace-order)))
