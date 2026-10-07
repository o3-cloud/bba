(ns bba.core
  "System prompt, agent loop, tool dispatch, extension loader, sessions."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bba.ext :as ext]
            [bba.provider :as provider]))

(def default-max-turns 50)

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

(defn load-session [file]
  (->> (str/split-lines (slurp file))
       (remove str/blank?)
       (map #(select-keys (json/parse-string % true) [:role :content]))
       replayable))

(defn log-message! [{:keys [session-file]} msg]
  (when session-file
    (spit session-file
          (str (json/generate-string (assoc (select-keys msg [:role :content]) :ts (System/currentTimeMillis))) "\n")
          :append true)))

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

;; ---------------------------------------------------------------- loop

(defn- show [ctx s] (when-let [p (or (:show ctx) (:print ctx))] (p s)))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "...") s))

(defn run-turns
  "Call the provider until it stops asking for tools.
  Returns {:status :ok|:max-turns, :text final-text, :messages all-messages}."
  [ctx messages]
  (let [call (or (ext/provider) (provider/from-env (:env ctx)))
        max-turns (or (:max-turns ctx) default-max-turns)]
    (loop [messages messages turn 0]
      (if (>= turn max-turns)
        {:status :max-turns :messages messages
         :text (str "stopped: reached max turns (" max-turns ")")}
        (let [_ (when-let [f (:on-request ctx)] (f))
              reply (call (cond-> {:messages messages :system (prompt-for ctx) :tools (tool-specs)}
                             (:on-text ctx) (assoc :on-text (:on-text ctx))))
              amsg {:role "assistant" :content (:content reply)}
              uses (filter #(= "tool_use" (:type %)) (:content reply))
              messages (conj messages amsg)]
          (log-message! ctx amsg)
          (if (empty? uses)
            {:status :ok :messages messages
             :text (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) (:content reply)))}
            (let [results (mapv (fn [u]
                                  (show ctx (str "→ " (:name u) " " (clip (json/generate-string (:input u)) 200)))
                                  (let [r (run-tool u ctx)]
                                    (show ctx (if (:is_error r)
                                                (str "← error: " (clip (:content r) 200))
                                                (str "← " (count (:content r)) " chars")))
                                    r))
                                uses)
                  umsg {:role "user" :content results}]
              (log-message! ctx umsg)
              (recur (conj messages umsg) (inc turn)))))))))

(defn user-message [text] {:role "user" :content [{:type "text" :text text}]})

;; ---------------------------------------------------------------- extensions

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
  "Remove extension registrations, then load every *.clj in the user and project folders."
  [ctx]
  (ext/reset-extensions!)
  (doseq [dir (extension-dirs ctx)
          :when (fs/directory? dir)
          f (sort (map str (fs/glob dir "*.clj")))]
    (load-extension! f)))
