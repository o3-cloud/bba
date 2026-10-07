(ns bba.main
  "Command line: print mode (-p) and the interactive loop."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.tools :as tools]))

(def usage "usage: bba [-p PROMPT] [--max-turns N] [--no-extensions]")

(defn parse-args [args]
  (loop [[a & more] args opts {}]
    (case a
      nil opts
      ("-p" "--print") (if (some? (first more))
                         (recur (rest more) (assoc opts :prompt (first more)))
                         (assoc opts :error "-p needs a prompt"))
      "--max-turns" (if-let [n (some-> (first more) parse-long)]
                      (recur (rest more) (assoc opts :max-turns n))
                      (assoc opts :error "--max-turns needs a number"))
      "--no-extensions" (recur more (assoc opts :no-extensions true))
      ("-h" "--help") (assoc opts :help true)
      (assoc opts :error (str "unknown argument: " a)))))

(defn- agent-run
  "One user message through the loop. Returns [status new-messages]."
  [ctx messages text]
  (let [umsg (core/user-message text)]
    (core/log-message! ctx umsg)
    (let [{:keys [status text messages]} (core/run-turns ctx (conj messages umsg))]
      (println text)
      [status messages])))

(defn- help-text []
  (str "commands: /reload /help /quit /exit"
       (str/join (map #(str " /" (:name %)) (sort-by :name (ext/commands))))))

(defn- builtin-commands [ctx]
  {"reload" (fn [_] (core/load-extensions! ctx)
              (println (str "reloaded: " (count (ext/tools)) " tools, "
                            (count (ext/commands)) " commands")))
   "help" (fn [_] (println (help-text)))})

(defn- run-command [ctx line]
  (let [[_ name args] (re-matches #"/(\S+)\s*(.*)" line)
        builtin (get (builtin-commands ctx) name)]
    (cond
      builtin (builtin args)
      (ext/command name) (try ((:handler (ext/command name)) args ctx)
                              (catch Exception e (ext/warn "command /" name " failed: " (ex-message e))))
      :else (println (str "unknown command: /" name " (try /help)")))))

(defn interactive [ctx ^java.io.BufferedReader in]
  (println (str "bba — session " (:session-file ctx) "\n" (help-text)))
  (loop [messages []]
    (print "> ") (flush)
    (let [line (.readLine in)]
      (cond
        (nil? line) 0
        (str/blank? line) (recur messages)
        (#{"/quit" "/exit"} (str/trim line)) 0
        (str/starts-with? line "/") (do (run-command ctx (str/trim line)) (recur messages))
        :else (recur (try (second (agent-run ctx messages line))
                          (catch Exception e
                            (ext/warn (ex-message e))
                            messages)))))))

(defn run
  "Run bba. Returns the exit code. `opts` = {:args :cwd :env :in}; *out*/*err* are used for output."
  [{:keys [args cwd env in]}]
  (let [{:keys [prompt max-turns no-extensions error help]} (parse-args args)
        home (or (get env "BBA_HOME") (str (fs/path (System/getProperty "user.home") ".bba")))]
    (cond
      help (do (println usage) 0)
      error (do (ext/warn error "\n" usage) 1)
      (and (some? prompt) (str/blank? prompt)) (do (ext/warn "empty prompt\n" usage) 1)
      :else
      (let [ctx {:cwd cwd :home home :env env
                 :max-turns (or max-turns (some-> (get env "BBA_MAX_TURNS") parse-long))
                 :print (when-not prompt println)}]
        (tools/register-builtins!)
        (if no-extensions (ext/reset-extensions!) (core/load-extensions! ctx))
        (if (and (nil? (ext/provider)) (str/blank? (get env "ANTHROPIC_API_KEY")))
          (do (binding [*out* *err*] (println "ANTHROPIC_API_KEY is not set")) 1)
          (let [ctx (assoc ctx :session-file (core/new-session-file cwd))]
            (ext/emit :session-start {} ctx)
            (if prompt
              (try (let [[status] (agent-run ctx [] prompt)]
                     (if (= status :ok) 0 1))
                   (catch Exception e (ext/warn (ex-message e)) 1))
              (interactive ctx in))))))))

(defn -main [& args]
  (let [code (try (run {:args args :cwd (str (fs/cwd)) :env (into {} (System/getenv))
                        :in (java.io.BufferedReader. (java.io.InputStreamReader. System/in))})
                  (catch Throwable e
                    (ext/warn (ex-message e))
                    (when (System/getenv "BBA_DEBUG") (.printStackTrace e))
                    1))]
    (System/exit code)))
