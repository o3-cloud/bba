(ns bba.main
  "Command line: print mode (-p) and the interactive loop."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.provider :as provider]
            [bba.md :as md]
            [bba.toolview :as toolview]
            [bba.tui :as tui]
            [bba.ui :as ui]
            [bba.world :as world]
            [bba.world-store :as store]))

(def usage (str "usage: bba [-p PROMPT] [-c] [--world NAME] [--provider ollama|anthropic|openai|openrouter|codex] [--model NAME]"
                " [--max-turns N] [--no-extensions] [--temporary]  (defaults: provider ollama, model deepseek-v4.1-flash:cloud)"))

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
      "--temporary" (recur more (assoc opts :temporary true))
      "--temp" (recur more (assoc opts :temporary true))
      "--world" (if (and (some? (first more)) (not (str/starts-with? (first more) "-")))
                  (recur (rest more) (assoc opts :world (first more)))
                  ;; before named worlds --world was a mode flag with no value: accept it, ignore it
                  (recur more (assoc opts :warn "--world needs a name (the old flag is ignored)")))
      ("-h" "--help") (assoc opts :help true)
      (assoc opts :error (str "unknown argument: " a)))))

;; ---------------------------------------------------------------- one turn

(defn- rows-used
  "Screen rows that `s` (no newlines) takes when printed from column 0 at `w` columns."
  [s w]
  (max 1 (long (Math/ceil (/ (md/width-of s) (double (max 1 w)))))))

(defn- stream-printer
  "Prints text as it streams, then redraws each finished line as rendered markdown:
  it moves the cursor up over the rows the raw text took, clears them, and prints the
  rendered rows. Table rows are held until the table ends, since a column's width
  depends on every row; then the whole table is redrawn at once. Anything taller
  than the screen has scrolled out of reach, so it is left as the terminal showed
  it. The renderer context is carried across lines, so a fenced block stays open and
  its rows are padded. A blank line still goes through the renderer, because inside
  a fence it is a panel row. Returns {:text f :end-line f :flush f :streamed? atom}."
  [ctx]
  (let [md-ctx (atom (or (:md ctx) (md/context {:width (tui/width)})))
        w (:width @md-ctx)
        h (tui/height)
        line (StringBuilder.)   ; the whole line so far
        shown (StringBuilder.)  ; the part of it already printed raw
        table (atom [])         ; raw table rows waiting for the table to end
        table-rows (atom 0)     ; screen rows those raw table rows take
        streamed (atom false)
        redraw! (fn [up s]      ; replace the `up` rows above (and this one) with s
                  (if (and (:render @md-ctx) (< up h))
                    (do (print (str "\r" (when (pos? up) (str "\u001b[" up "A")) "\u001b[J" s)) true)
                    false))
        table-str (fn [] (when (seq @table)
                           (str (md/render @md-ctx (str/join "\n" @table)) "\n")))
        reset-table! (fn [] (reset! table []) (reset! table-rows 0))
        end-line (fn []
                   (let [l (str line)]
                     (if (and (:render @md-ctx) (not (:fence @md-ctx)) (md/table-row? l))
                       (do (println (subs l (.length shown)))
                           (swap! table conj l)
                           (swap! table-rows + (rows-used l w)))
                       (let [up (+ @table-rows (dec (rows-used (str shown) w)))
                             tbl (table-str)
                             [c rows] (md/md-line @md-ctx l)]
                         (reset! md-ctx c)
                         (when-not (redraw! up (str tbl (md/rows-str rows)))
                           (print (subs l (.length shown))))
                         (println)
                         (reset-table!))))
                   (.setLength line 0)
                   (.setLength shown 0))
        finish-line (fn []
                      (cond (pos? (.length line)) (end-line)
                            (seq @table) (do (redraw! @table-rows (table-str)) (reset-table!))))]
    {:streamed? streamed
     :end-line end-line
     :flush finish-line
     :text (fn [s]
             (reset! streamed true)
             (doseq [part (str/split s #"(?<=\n)")]
               (if (str/ends-with? part "\n")
                 (do (.append line (subs part 0 (dec (count part))))
                     (end-line))
                 (do (.append line part) (.append shown part) (print part))))
             (flush))}))

(defonce ^:private call-seq (atom 0))

(defn- detail-file
  "Where a tool call's full detail goes: a folder beside the session file, which
  sits in the git-ignored sessions folder. nil without a session."
  [ctx tool]
  (when-let [sf (:session-file ctx)]
    (str (fs/absolutize (fs/path (str (fs/strip-ext sf) ".calls")
                                 (format "%03d-%s.md" (swap! call-seq inc) tool))))))

(defn- tool-shower
  "The :show handler for a terminal: each call is one collapsed line whose label
  links to a file with its full detail, and each finished call is added to
  (:calls ctx) for Ctrl-O."
  [ctx live out]
  (let [pending (atom nil)
        width (or (get-in ctx [:md :width]) (tui/width))]
    (fn [{:keys [phase tool input content error?]}]
      (when @live
        ((:flush out))
        (if (= phase :call)
          (let [f (detail-file ctx tool)]
            (reset! pending f)
            (ui/log! (toolview/call-str tool input width (some->> f (str "file://"))))
            (ui/start! (str "running " tool "...")))
          (let [d (toolview/detail-md tool input content)]
            (when-let [f @pending]
              (try (fs/create-dirs (fs/parent f)) (spit f d)
                   (catch Exception _ nil)))  ; a missing detail file must not stop the turn
            (some-> (:calls ctx) (swap! conj d))
            (ui/log! (toolview/result-str tool content error? width))
            (ui/stop!)))))))

(defn show-calls
  "Ctrl-O: the full detail of the last turn's tool calls, rendered, in a full-screen
  pager. Closing it (q, Esc or Ctrl-O again) brings back the conversation as it was."
  [ctx]
  (let [ds (some-> (:calls ctx) deref)]
    (if (empty? ds)
      (println (ui/dim "(no tool calls in the last turn)"))
      (tui/page! (md/render (or (:md ctx) (md/context {:width (tui/width)})) (str/join "\n" ds))))))

(defn- agent-run
  "One user message through the loop. Returns [status new-messages].
  With :stream? in ctx: spinner while waiting, streamed text, dim tool lines."
  [ctx messages text]
  (let [umsg (core/user-message text)
        live (:live ctx (atom true))
        out (when (:stream? ctx) (stream-printer ctx))
        ctx (cond-> ctx
              out (assoc :on-request #(when @live (ui/start! "thinking..."))
                         :on-text #(when @live (ui/stop!) ((:text out) %))
                         :show (tool-shower ctx live out)))]
    (some-> (:calls ctx) (reset! []))
    (core/log-message! ctx umsg)
    (try
      (let [{:keys [status text messages]} (core/run-turns ctx (conj messages umsg))]
        (ui/stop!)
        (if (and out @(:streamed? out))
          ((:flush out))
          (println (md/render (or (:md ctx) (md/context {:width (tui/width)})) text)))
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
  (str "commands: /new /world [NAME] /reload /provider [NAME [MODEL]] /model [NAME] /clear /help /quit /exit"
       (str/join (map #(str " /" (:name %)) (sort-by :name (ext/commands))))))

(defn- show-model [env]
  (println (str "provider: " (provider/provider-name env) ", model: " (provider/model-name env)
                (when (ext/provider) " (an extension replaced the provider; it ignores this)"))))

(defn- switch-provider
  "No NAME: select a provider in a terminal, else list providers. NAME [MODEL]: switch."
  [ctx args]
  (let [[name model] (str/split (str/trim (str args)) #"\s+")
        model (not-empty model)]
    (if (str/blank? name)
      (if (:tty? ctx)
        (if-let [selected (tui/pick! "Providers" (sort (keys provider/providers))
                                     (provider/provider-name (:env ctx)))]
          (switch-provider ctx selected)
          ctx)
        (do (show-model (:env ctx))
            (println (str "providers: " (str/join ", " (sort (keys provider/providers)))))
            ctx))
      (let [env (cond-> (assoc (:env ctx) "BBA_PROVIDER" (str/lower-case name))
                  (not= (str/lower-case name) (provider/provider-name (:env ctx))) (dissoc "BBA_MODEL")
                  model (assoc "BBA_MODEL" model))]
        (if-let [problem (provider/check-env env)]
          (do (ext/warn problem "; still using " (provider/provider-name (:env ctx))) ctx)
          (do (show-model env) (assoc ctx :env env)))))))

(defn- switch-model
  "No NAME: select a model in a terminal, else list models. NAME: use it next."
  [ctx name]
  (if (str/blank? name)
    (let [models (provider/model-catalog (:env ctx))]
      (if (:tty? ctx)
        (if-let [selected (tui/pick! (str "Models for " (provider/provider-name (:env ctx)))
                                     models (provider/model-name (:env ctx)))]
          (switch-model ctx selected)
          ctx)
        (do (show-model (:env ctx))
            (println (str "models: " (str/join ", " models)))
            ctx)))
    (let [env (assoc (:env ctx) "BBA_MODEL" name)]
      (show-model env)
      (assoc ctx :env env))))

(defn- builtin-commands
  "Each fn takes [ctx args] and returns the ctx to use next."
  []
  {"reload" (fn [ctx _] (core/load-extensions! ctx)
              ;; extensions were dropped and reloaded: let them re-initialize
              (ext/emit :session-start {} ctx)
              (println (str "reloaded: " (count (ext/tools)) " tools, "
                            (count (ext/commands)) " commands"))
              ctx)
   "provider" switch-provider
   "model" switch-model
   "world" (fn [ctx args]  ; a switch tells the model with the next message
             (if-let [i (world/world-command args)]
               (assoc ctx :world (:name i) :note (world/switch-note i))
               ctx))
   "help" (fn [ctx _] (println (help-text)) ctx)
   "clear" (fn [ctx _] (ui/clear-screen) ctx)})

(defn- run-command
  "Run a /command. Returns the ctx to use next."
  [ctx line]
  (let [[_ name args] (re-matches #"/(\S+)\s*(.*)" line)
        builtin (get (builtin-commands) name)
        command (ext/command name)]
    (cond
      builtin (builtin ctx (str/trim args))
      command (do (try ((:handler command) args ctx)
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
   (println (ui/dim (str (help-text) (when (:tty? ctx) " | Enter sends, \\ or Alt+Enter = new line, Ctrl-O shows tool calls, Ctrl-C stops"))))
   (let [read-entry (line-reader ctx in)]
     (loop [ctx (assoc ctx :calls (atom [])) messages messages]
       (let [line (read-entry)]
         (cond
           (nil? line) 0
           (= :cancel line) (do (println (ui/dim "(Ctrl-D or /quit to exit)")) (recur ctx messages))
           (= :expand line) (do (show-calls ctx) (recur ctx messages))
           (str/blank? line) (recur ctx messages)
           (#{"/quit" "/exit"} (str/trim line)) 0
           (= "/new" (str/trim line))
           (let [ctx (assoc ctx :session-file (core/new-session-file (:cwd ctx)))]
             (println (ui/bold "bba") "— new session" (ui/dim (:session-file ctx)))
             (ext/emit :session-start {} ctx)
             (recur ctx []))
           (str/starts-with? (str/triml line) "/") (recur (run-command ctx (str/trim line)) messages)
           :else
           (let [live (atom true)
                 text (if-let [note (:note ctx)] (str note "\n\n" line) line)
                 r (try (with-cancel #(agent-run (assoc ctx :live live) messages text) live)
                        (catch Exception e
                          (ext/warn (ui/red (or (ex-message e) "error")))
                          nil))]
             (cond
               (= ::cancelled r) (do (ui/stop!) (println) (println (ui/yellow "(cancelled)")) (recur ctx messages))
               r (recur (dissoc ctx :note) (second r))
               :else (recur ctx messages)))))))))

;; ---------------------------------------------------------------- entry

(defn- run-session
  "Register tools, load extensions, check the provider, then run -p or the interactive loop."
  [{:keys [cwd env] :as ctx} {:keys [prompt no-extensions] :as opts} in world]
  (core/load-extensions! ctx no-extensions)
  (let [problem (when-not (ext/provider) (provider/check-env env))]
    (if (and problem prompt)
      (do (binding [*out* *err*] (println problem)) 1)
      (let [old (when (:continue opts)
                  (or (:session opts)
                      (do (ext/warn "no session to continue here; starting a new one") nil)))
            ctx (assoc ctx :session-file (or old (core/new-session-file cwd)) :world (:name world))
            messages (if old (core/load-session old) [])]
        (when problem (ext/warn problem "; manual /commands only"))
        (when-not prompt
          (println (ui/dim (str (when (:created world) (str "created " (store/world-dir cwd (:name world)) "/world.edn; "))
                                "world: " (:name world) (when (not= 'world (:ns world)) (str " (ns " (:ns world) ")"))
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
  (let [{:keys [prompt max-turns error help warn] :as opts} (parse-args args)
        env (cond-> env (:provider opts) (assoc "BBA_PROVIDER" (:provider opts))
                    (:model opts) (assoc "BBA_MODEL" (:model opts)))
        home (or (get env "BBA_HOME") (str (fs/path (System/getProperty "user.home") ".bba")))]
    (ui/enable! tty?)
    (cond
      help (do (println usage) 0)
      error (do (ext/warn error "\n" usage) 1)
      (and (some? prompt) (str/blank? prompt)) (do (ext/warn "empty prompt\n" usage) 1)
      :else
      (let [_ (when warn (ext/warn warn))
            old (when (:continue opts) (core/latest-session cwd))
            opts (assoc opts :session old)
            ;; -c resumes in the session's world unless --world names one
            from-session (when-let [w (some-> old core/session-world)]
                           (if (store/exists? cwd w) w
                               (do (ext/warn "the session's world " w " is gone; using the last active world") nil)))
            temp-source (or (:world opts) from-session (store/active cwd) store/default-world)
            temp (when (:temporary opts)
                   (try (store/temporary-fork! cwd temp-source)
                        (catch Exception e (ext/warn (or (ex-message e) "temporary fork failed")) nil)))
            ctx {:cwd cwd :home home :env env
                 :max-turns (or max-turns (some-> (get env "BBA_MAX_TURNS") parse-long))
                 :print (when-not prompt println)
                 :tty? (boolean tty?)
                 :md (md/context {:width (when tty? (tui/width)) :render (ui/enabled?)})
                 :stream? (boolean (and tty? (not prompt)))}
            world (try {:info (world/start! (assoc ctx :world (or (some-> temp :name) (:world opts) from-session)))}
                       (catch clojure.lang.ExceptionInfo e {:error (ex-message e)}))]
        (if (:error world)
          (do (ext/warn (:error world)) 1)
          (try (run-session (assoc ctx :system-prompt world/system-prompt) opts in (:info world))
               (finally (world/stop!))))))))

(defn -main [& args]
  (let [code (try (run {:args args :cwd (str (fs/cwd)) :env (into {} (System/getenv))
                        :in (java.io.BufferedReader. (java.io.InputStreamReader. System/in))
                        :tty? (tui/tty?)})
                  (catch Throwable e
                    (ext/warn (ex-message e))
                    (when (System/getenv "BBA_DEBUG") (.printStackTrace e))
                    1))]
    (System/exit code)))
