(ns bba.main
  "Command line: print mode (-p) and the interactive loop."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.provider :as provider]
            [bba.tools :as tools]
            [bba.tui :as tui]
            [bba.ui :as ui]))

(def usage (str "usage: bba [-p PROMPT] [-c] [--provider anthropic|openai|openrouter|ollama] [--model NAME]"
                " [--max-turns N] [--no-extensions] [--world]"))

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
      ("--provider" "--model") (if (some? (first more))
                                 (recur (rest more) (assoc opts (keyword (subs a 2)) (first more)))
                                 (assoc opts :error (str a " needs a value")))
      ("-c" "--continue") (recur more (assoc opts :continue true))
      "--no-extensions" (recur more (assoc opts :no-extensions true))
      "--world" (recur more (assoc opts :world true))
      ("-h" "--help") (assoc opts :help true)
      (assoc opts :error (str "unknown argument: " a)))))

;; ---------------------------------------------------------------- one turn

(defn- stream-printer
  "Prints streamed text as it arrives. When a line ends, redraws it with markdown
  formatting (if it fit on one screen row). Returns {:text f :end-line f :streamed? atom}."
  []
  (let [line (StringBuilder.) fence (atom false) streamed (atom false) w (tui/width)
        end-line (fn []
                   (when (pos? (.length line))
                     (let [l (str line) [f r] (ui/md-line @fence l)]
                       (reset! fence f)
                       (when (< (count l) w) (print (str "\r\u001b[K" r)))
                       (println)
                       (.setLength line 0))))]
    {:streamed? streamed
     :end-line end-line
     :text (fn [s]
             (reset! streamed true)
             (doseq [part (str/split s #"(?<=\n)")]
               (if (str/ends-with? part "\n")
                 (do (print (subs part 0 (dec (count part))))
                     (.append line (subs part 0 (dec (count part))))
                     (if (pos? (.length line)) (end-line) (println)))
                 (do (print part) (.append line part))))
             (flush))}))

(defn- agent-run
  "One user message through the loop. Returns [status new-messages].
  With :stream? in ctx: spinner while waiting, streamed text, dim tool lines."
  [ctx messages text]
  (let [umsg (core/user-message text)
        live (:live ctx (atom true))
        out (when (:stream? ctx) (stream-printer))
        ctx (cond-> ctx
              out (assoc :on-request #(when @live (ui/start! "thinking..."))
                         :on-text #(when @live (ui/stop!) ((:text out) %))
                         :show (fn [s]  ; "→ tool ..." before a tool runs, "← ..." after
                                 (when @live
                                   ((:end-line out))
                                   (ui/log! (ui/dim s))
                                   (if-let [[_ tool] (re-find #"^→ (\S+)" s)]
                                     (ui/start! (str "running " tool "..."))
                                     (ui/stop!))))))]
    (core/log-message! ctx umsg)
    (try
      (let [{:keys [status text messages]} (core/run-turns ctx (conj messages umsg))]
        (ui/stop!)
        (if (and out @(:streamed? out))
          ((:end-line out))
          (println (ui/md text)))
        [status messages])
      (finally (ui/stop!)))))

(defn- with-cancel
  "Run (f) on another thread. Ctrl-C (SIGINT) cancels it and returns ::cancelled."
  [f live]
  (let [fut (future (f))
        sig (sun.misc.Signal. "INT")
        old (sun.misc.Signal/handle sig (reify sun.misc.SignalHandler
                                          (handle [_ _] (reset! live false) (future-cancel fut))))]
    (try @fut
         (catch java.util.concurrent.CancellationException _ ::cancelled)
         (catch java.util.concurrent.ExecutionException e (throw (.getCause e)))
         (finally (sun.misc.Signal/handle sig old)))))

;; ---------------------------------------------------------------- commands

(defn- help-text []
  (str "commands: /reload /provider [NAME] /model [NAME] /clear /help /quit /exit"
       (str/join (map #(str " /" (:name %)) (sort-by :name (ext/commands))))))

(defn- show-model [env]
  (println (str "provider: " (provider/provider-name env) ", model: " (provider/model-name env)
                (when (ext/provider) " (an extension replaced the provider; it ignores this)"))))

(defn- switch-provider
  "No NAME: show the current provider and the list. NAME: switch and use its default model."
  [ctx name]
  (if (str/blank? name)
    (do (show-model (:env ctx))
        (println (str "providers: " (str/join ", " (sort (keys provider/providers)))))
        ctx)
    (let [env (-> (:env ctx) (assoc "BBA_PROVIDER" (str/lower-case name)) (dissoc "BBA_MODEL"))]
      (if-let [problem (provider/check-env env)]
        (do (ext/warn problem "; still using " (provider/provider-name (:env ctx))) ctx)
        (do (show-model env) (assoc ctx :env env))))))

(defn- switch-model
  "No NAME: show the current model. NAME: use it for the next messages (not checked until then)."
  [ctx name]
  (if (str/blank? name)
    (do (show-model (:env ctx)) ctx)
    (let [env (assoc (:env ctx) "BBA_MODEL" name)]
      (show-model env)
      (assoc ctx :env env))))

(defn- builtin-commands
  "Each fn takes [ctx args] and returns the ctx to use next."
  []
  {"reload" (fn [ctx _] (core/load-extensions! ctx)
              (println (str "reloaded: " (count (ext/tools)) " tools, "
                            (count (ext/commands)) " commands"))
              ctx)
   "provider" switch-provider
   "model" switch-model
   "help" (fn [ctx _] (println (help-text)) ctx)
   "clear" (fn [ctx _] (ui/clear-screen) ctx)})

(defn- run-command
  "Run a /command. Returns the ctx to use next."
  [ctx line]
  (let [[_ name args] (re-matches #"/(\S+)\s*(.*)" line)
        builtin (get (builtin-commands) name)]
    (cond
      builtin (builtin ctx (str/trim args))
      (ext/command name) (do (try ((:handler (ext/command name)) args ctx)
                                  (catch Exception e (ext/warn "command /" name " failed: " (ex-message e))))
                             ctx)
      :else (do (println (ui/red (str "unknown command: /" name " (try /help)"))) ctx))))

;; ---------------------------------------------------------------- interactive

(defn- line-reader
  "Returns a fn that reads one entry: text, nil at EOF, or :cancel (Ctrl-C at an empty prompt).
  On a terminal it uses the line editor; otherwise it reads plain lines."
  [ctx ^java.io.BufferedReader in]
  (if (:tty? ctx)
    (let [file (str (fs/path (:home ctx) "history"))
          hist (atom (tui/load-history file))]
      #(tui/read-line! {:prompt "› " :shown-prompt (ui/cyan "› ") :hist hist :history-file file}))
    #(do (print "› ") (flush) (.readLine in))))

(defn interactive
  ([ctx in] (interactive ctx in []))
  ([ctx in messages]
   (println (ui/bold "bba") "— session" (ui/dim (:session-file ctx)))
   (println (str "provider: " (provider/provider-name (:env ctx)) ", model: " (provider/model-name (:env ctx))))
   (when (seq messages) (println (ui/dim (str "continuing: " (count messages) " messages"))))
   (println (ui/dim (str (help-text) (when (:tty? ctx) " | Enter sends, \\ or Alt+Enter = new line, Ctrl-C stops"))))
   (let [read-entry (line-reader ctx in)]
     (loop [ctx ctx messages messages]
       (let [line (read-entry)]
         (cond
           (nil? line) 0
           (= :cancel line) (do (println (ui/dim "(Ctrl-D or /quit to exit)")) (recur ctx messages))
           (str/blank? line) (recur ctx messages)
           (#{"/quit" "/exit"} (str/trim line)) 0
           (str/starts-with? (str/triml line) "/") (recur (run-command ctx (str/trim line)) messages)
           :else
           (let [live (atom true)
                 r (try (with-cancel #(agent-run (assoc ctx :live live) messages line) live)
                        (catch Exception e
                          (ext/warn (ui/red (or (ex-message e) "error")))
                          nil))]
             (cond
               (= ::cancelled r) (do (ui/stop!) (println) (println (ui/yellow "(cancelled)")) (recur ctx messages))
               r (recur ctx (second r))
               :else (recur ctx messages)))))))))

;; ---------------------------------------------------------------- entry

(defn- run-session
  "Register tools, load extensions, check the provider, then run -p or the interactive loop."
  [{:keys [cwd env] :as ctx} {:keys [prompt no-extensions] :as opts} in world]
  (when-not world (tools/register-builtins!))  ; world mode offers only the world tools (and extensions)
  (if no-extensions (ext/reset-extensions!) (core/load-extensions! ctx))
  (let [problem (when-not (ext/provider) (provider/check-env env))]
    (if (and problem (or (not world) prompt))
      (do (binding [*out* *err*] (println problem)) 1)
      (let [old (when (:continue opts)
                  (or (core/latest-session cwd)
                      (do (ext/warn "no session to continue here; starting a new one") nil)))
            ctx (assoc ctx :session-file (or old (core/new-session-file cwd)))
            messages (if old (core/load-session old) [])]
        (when problem (ext/warn problem "; world mode: manual /commands only"))
        (when (and world (not prompt))
          (println (ui/dim (str (when (:created world) "created .bba/world/world.edn; ") "world: " (:ns world)
                                ", revision " (:rev world) ", " (:functions world) " functions"))))
        (ext/emit :session-start {} ctx)
        (if prompt
          (try (let [[status] (agent-run ctx messages prompt)]
                 (if (= status :ok) 0 1))
               (catch Exception e (ext/warn (ex-message e)) 1))
          (interactive ctx in messages))))))

(defn run
  "Run bba. Returns the exit code. `opts` = {:args :cwd :env :in :tty?}; *out*/*err* are used for output."
  [{:keys [args cwd env in tty?]}]
  (let [{:keys [prompt max-turns error help] :as opts} (parse-args args)
        env (cond-> env (:provider opts) (assoc "BBA_PROVIDER" (:provider opts))
                        (:model opts) (assoc "BBA_MODEL" (:model opts)))
        home (or (get env "BBA_HOME") (str (fs/path (System/getProperty "user.home") ".bba")))]
    (ui/enable! tty?)
    (cond
      help (do (println usage) 0)
      error (do (ext/warn error "\n" usage) 1)
      (and (some? prompt) (str/blank? prompt)) (do (ext/warn "empty prompt\n" usage) 1)
      :else
      (let [ctx {:cwd cwd :home home :env env
                 :max-turns (or max-turns (some-> (get env "BBA_MAX_TURNS") parse-long))
                 :print (when-not prompt println)
                 :tty? (boolean tty?)
                 :stream? (boolean (and tty? (not prompt)))}
            world (when (:world opts)  ; bba.world is loaded only with --world
                    (try {:info ((requiring-resolve 'bba.world/start!) ctx)}
                         (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})))]
        (if (:error world)
          (do (ext/warn (:error world)) 1)
          (try (run-session (cond-> ctx world (assoc :system-prompt (requiring-resolve 'bba.world/system-prompt)))
                            opts in (:info world))
               (finally (when world ((requiring-resolve 'bba.world/stop!))))))))))

(defn -main [& args]
  (let [code (try (run {:args args :cwd (str (fs/cwd)) :env (into {} (System/getenv))
                        :in (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
                        :tty? (tui/tty?)})
                  (catch Throwable e
                    (ext/warn (ex-message e))
                    (when (System/getenv "BBA_DEBUG") (.printStackTrace e))
                    1))]
    (System/exit code)))
