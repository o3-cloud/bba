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
       "You can extend yourself: write a Clojure file to .bba/extensions/ that calls "
       "bba.ext/register-tool!, register-command! or on!. The user types /reload to load it."
       (agents-md home)
       (agents-md cwd)))

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
                 (catch Exception e {:content (str "tool error: " (ex-message e)) :is-error true}))]
    (cond-> {:type "tool_result" :tool_use_id id :content (str (:content result))}
      (:is-error result) (assoc :is_error true))))

;; ---------------------------------------------------------------- loop

(defn- show [ctx s] (when-let [p (:print ctx)] (p s)))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "...") s))

(defn run-turns
  "Call the provider until it stops asking for tools.
  Returns {:status :ok|:max-turns, :text final-text, :messages all-messages}."
  [ctx messages]
  (let [call (or (ext/provider) (provider/anthropic (:env ctx)))
        max-turns (or (:max-turns ctx) default-max-turns)]
    (loop [messages messages turn 0]
      (if (>= turn max-turns)
        {:status :max-turns :messages messages
         :text (str "stopped: reached max turns (" max-turns ")")}
        (let [reply (call {:messages messages :system (system-prompt ctx) :tools (tool-specs)})
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
