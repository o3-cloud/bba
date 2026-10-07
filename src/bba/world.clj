(ns bba.world
  "World mode (after Jiti, ghuntley.com/lisp): grow one live namespace through two
  operations that share one evaluator — develop (add / redefine / remove) and execute
  (call existing code). Every attempt runs against a checkpoint. Any failure restores
  it by rebuilding the namespace from the last accepted snapshot (DR-1). Accepted
  attempts become immutable revisions on disk (bba.world-store).
  Not Common Lisp: there are no conditions or restarts. A failure restores, then reports."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.node :as rn]
            [rewrite-clj.parser :as rp]
            [bba.ext :as ext]
            [bba.ui :as ui]
            [bba.world-store :as store]))

;; {:dir :adapter :ns-name :pid :snapshot {:log [..]} :rev n :base-rev n
;;  :checks {:invariants [..] :goals [..]} :restore-ms n :timeouts n}
(defonce state (atom nil))

(defn- now [] (System/currentTimeMillis))
(defn- wns [] (the-ns (:ns-name @state)))
(defn- publics [] (ns-publics (wns)))

;; ---------------------------------------------------------------- parse

(def ^:private def-kinds '{defn :fn defmacro :macro def :var defonce :var defmulti :var})

(defn- head [node]
  (when (= :list (rn/tag node))
    (let [kids (remove rn/whitespace-or-comment? (rn/children node))]
      (try [(rn/sexpr (first kids)) (some-> (second kids) rn/sexpr)] (catch Exception _ nil)))))

(defn parse-forms
  "Split code into top-level forms with their exact source text.
  Returns {:forms [{:source :defines :kind}]} or {:error result-map}."
  [code]
  (try
    (let [nodes (->> (rn/children (rp/parse-string-all (or code "")))
                     (remove #(or (rn/whitespace-or-comment? %) (= :uneval (rn/tag %)))))
          forms (mapv (fn [n]
                        (let [[h nm] (head n)]
                          (cond-> {:source (rn/string n) :head h}
                            (and (def-kinds h) (symbol? nm)) (assoc :defines nm :kind (def-kinds h)))))
                      nodes)]
      (if-let [bad (some #(when ('#{ns in-ns} (:head %)) %) forms)]
        {:error {:status :error :class "reader" :form (:source bad)
                 :message "ns / in-ns is not allowed: every form runs in the world namespace"}}
        {:forms (mapv #(dissoc % :head) forms)}))
    (catch Exception e {:error {:status :error :class "reader" :message (ex-message e)}})))

;; ---------------------------------------------------------------- checkpoint / restore

(defn- stateful? [x] (or (instance? clojure.lang.Atom x) (instance? clojure.lang.Volatile x)))

(defn- live-state
  "Contents of every public var that holds an atom or a volatile."
  []
  (into {} (for [[s v] (publics) :let [x @v] :when (stateful? x)] [s @x])))

(defn- checkpoint []
  {:log (get-in @state [:snapshot :log]) :state (live-state)
   :roots (into {} (for [[s v] (publics)] [s @v]))})

(defn- replay!
  "Discard the world namespace and rebuild it from an ordered log. A runaway thread
  from an earlier attempt keeps only the old, detached namespace."
  [log]
  (let [n (:ns-name @state)]
    (remove-ns n)
    (let [w (create-ns n)]
      (binding [*ns* w]
        (load-string "(clojure.core/require '[clojure.string :as str] '[clojure.set :as set])")
        (doseq [{:keys [source remove]} log]
          (if remove (ns-unmap w remove) (load-string source))))
      w)))

(defn restore!
  "Rebuild the world from `cp` = {:log [..] :state {sym value}}: replay, then reset state vars."
  [{:keys [log] vals :state}]
  (let [t0 (now) w (replay! log)]
    (doseq [[s v] vals :let [var (get (ns-publics w) s)] :when var :let [x @var]]
      (cond (instance? clojure.lang.Atom x) (reset! x v)
            (instance? clojure.lang.Volatile x) (vreset! x v)))
    (swap! state assoc :restore-ms (- (now) t0))
    w))

;; ---------------------------------------------------------------- evaluate

(defn bounded
  "pr-str with print bounds, cut at `max-chars` with a note giving the full length."
  [v max-chars]
  (let [s (binding [*print-length* 100 *print-level* 10] (pr-str v))]
    (if (> (count s) max-chars)
      (str (subs s 0 max-chars) " ...[truncated: " (count s) " chars]")
      s)))

(defn- error-result [^Throwable e extra]
  (merge {:status :error :class (.getName (class e)) :message (or (ex-message e) (str e))}
         (when-let [d (ex-data e)] {:data (bounded d 1000)})
         extra))

(defn- run-checks [w checks]
  (mapv (fn [{:keys [name form]}]
          (try {:name name :ok (boolean (binding [*ns* w] (load-string (pr-str form))))}
               (catch Throwable e {:name name :ok false :error (or (ex-message e) (str e))})))
        checks))

(defn- run-body
  "Runs on the eval thread. Never throws; returns a result map."
  [{:keys [op forms removals]} cp {:keys [invariants goals max-result-chars]}]
  (let [w (wns)]
    (loop [[fm & more] forms i 0 v nil]
      (if fm
        (let [r (try {:v (binding [*ns* w] (load-string (:source fm)))}
                     (catch Throwable e {:e e}))]
          (if (:e r)
            (error-result (:e r) {:form (:source fm) :form-index i})
            (recur more (inc i) (:v r))))
        (let [unknown (remove #(contains? (ns-publics w) %) removals)
              _ (when (empty? unknown) (doseq [s removals] (ns-unmap w s)))
              now-pub (ns-publics w)
              new-vars (when (= op :execute) (sort (remove (:roots cp) (keys now-pub))))
              redefined (when (= op :execute)
                          (sort (for [[s root] (:roots cp)
                                      :let [var (get now-pub s)]
                                      :when (or (nil? var) (not (identical? root @var)))] s)))
              inv (when (not= op :preview) (run-checks w invariants))]
          (cond
            (seq unknown) {:status :error :class "remove" :message (str "unknown name(s): " (str/join " " unknown))}
            (seq new-vars) {:status :rejected :reason :new-var :vars (vec new-vars)
                            :message "execute_form must not define vars; use develop_form"}
            (seq redefined) {:status :rejected :reason :redefined :vars (vec redefined)
                             :message "execute_form must not redefine or remove vars; use develop_form"}
            (some (complement :ok) inv) {:status :rejected :reason :invariant
                                         :failed (vec (for [c inv :when (not (:ok c))] (dissoc c :ok)))}
            (= op :preview) {:status :preview :value (bounded v max-result-chars)}
            :else {:status :accepted :value (bounded v max-result-chars)
                   :checks {:invariants inv :goals (run-checks w goals)}}))))))

(defn- printable? [v]
  (try (= v (edn/read-string (binding [*print-length* nil *print-level* nil] (pr-str v))))
       (catch Exception _ false)))

(defn- publish-snapshot!
  "Write a revision with `log` and the live state; update the in-memory snapshot."
  [type log input removals extra]
  (let [{:keys [dir rev]} @state
        n (store/next-rev dir)
        log (mapv #(if (:rev %) % (assoc % :rev n)) log)
        live (or (:state extra) (live-state))
        keep-state (into {} (filter (comp printable? val)) live)
        unprintable (vec (sort (remove (set (keys keep-state)) (keys live))))
        op-id (str (random-uuid))]
    (store/publish! dir (merge {:rev n :op-id op-id :ts (str (java.time.Instant/now)) :type type
                                :base-rev rev :input input :remove (vec removals)
                                :log log :state keep-state :unprintable unprintable :rollback-of nil}
                               (dissoc extra :state)))
    (when (seq unprintable) (ext/warn "world state not saved (cannot be printed as data): " (str/join " " unprintable)))
    (swap! state assoc :snapshot {:log log} :rev n :base-rev n)
    {:revision n :op-id op-id}))

(defn- outcome [{:keys [status reason class]}]
  (case status
    :accepted "accepted" :preview "preview"
    :rejected (str "rejected-" (name reason))
    (if (#{"timeout" "aborted"} class) class "error")))

(defn- finish [op t0 r]
  (let [r (assoc r :op (name op) :ms (- (now) t0))]
    (when-let [dir (:dir @state)]
      (store/log-op! dir {:op-id (or (:op-id r) (str (random-uuid))) :op (name op) :outcome (outcome r) :ms (:ms r) :revision (:rev @state)}))
    r))

(defn attempt!
  "The one evaluator path. `op` is :develop, :execute or :preview."
  [{:keys [op code remove]}]
  (let [t0 (now) removals (mapv symbol remove) parsed (parse-forms code)]
    (cond
      (:error parsed) (finish op t0 (:error parsed))
      (and (empty? (:forms parsed)) (empty? removals))
      (finish op t0 {:status :error :class "reader" :message "nothing to evaluate"})
      (and (seq removals) (not= op :develop))
      (finish op t0 {:status :error :class "remove" :message "only develop can remove names"})
      :else
      (let [adapter (:adapter @state) cp (checkpoint)
            fut (future (run-body {:op op :forms (:forms parsed) :removals removals} cp adapter))
            r (try (let [r (deref fut (:timeout-ms adapter) ::timeout)]
                     (if (= ::timeout r)
                       (do (future-cancel fut)
                           (swap! state update :timeouts (fnil inc 0))
                           {:status :error :class "timeout"
                            :message (str "evaluation exceeded " (:timeout-ms adapter)
                                          " ms; a runaway thread may still use CPU until bba exits")})
                       r))
                   (catch InterruptedException e
                     (future-cancel fut)
                     (restore! cp)
                     (finish op t0 {:status :error :class "aborted" :message "interrupted"})
                     (throw e)))]
        (if (and (= :accepted (:status r)) (not= op :preview))
          (try (let [forms (map #(select-keys % [:source :defines :kind]) (:forms parsed))
                     log (cond-> (:log cp)
                           (= op :develop) (-> (into forms) (into (map (fn [s] {:remove s}) removals))))
                     pub (publish-snapshot! op log code removals {})]
                 (swap! state assoc :checks (:checks r))
                 (finish op t0 (merge r pub {:defined (vec (keep :defines (:forms parsed))) :removed removals})))
               (catch Exception e
                 (restore! cp)
                 (finish op t0 (error-result e {:class "store" :restored-to (:rev @state)}))))
          (do (restore! cp)
              (finish op t0 (cond-> r (not= op :preview) (assoc :restored-to (:rev @state))))))))))

(defn rollback!
  "Publish the state of revision `n` as a new revision. History is kept."
  [n]
  (let [{:keys [dir adapter rev]} @state t0 (now) target (some->> n (store/read-rev dir))]
    (if-not target
      (finish :rollback t0 {:status :error :class "rollback" :message (str "unknown revision: " n)})
      (let [cp (checkpoint)]
        (try
          (let [w (restore! target) inv (run-checks w (:invariants adapter))]
            (if (some (complement :ok) inv)
              (do (restore! cp)
                  (finish :rollback t0 {:status :rejected :reason :invariant :restored-to rev
                                        :failed (vec (for [c inv :when (not (:ok c))] (dissoc c :ok)))}))
              (let [pub (publish-snapshot! :rollback (:log target) (str "/rollback " n) []
                                           {:rollback-of n :state (:state target)})]
                (swap! state assoc :checks {:invariants inv :goals (run-checks w (:goals adapter))})
                (finish :rollback t0 (merge {:status :accepted :rollback-of n} pub
                                            (when (= n rev) {:note "rolled back to the current revision"}))))))
          (catch Exception e
            (restore! cp)
            (finish :rollback t0 (error-result e {:restored-to rev}))))))))

;; ---------------------------------------------------------------- catalogue

(defn catalogue
  "Accepted def forms (last definition wins, removals drop), with live arglists and doc."
  []
  (let [pub (publics)]
    (->> (get-in @state [:snapshot :log])
         (reduce (fn [m {:keys [defines remove] :as e}]
                   (cond remove (dissoc m remove)
                         defines (assoc m defines {:name defines :kind (:kind e) :source (:source e) :revision (:rev e)})
                         :else m))
                 (sorted-map))
         vals
         (keep (fn [e] (when-let [v (get pub (:name e))]
                         (assoc e :arglists (:arglists (meta v)) :doc (:doc (meta v)))))))))

(defn- first-line [s] (some-> s str/split-lines first str/trim))

;; ---------------------------------------------------------------- prompt

(defn system-prompt [_ctx]
  (let [{:keys [ns-name adapter checks]} @state
        cat (catalogue)
        status (fn [cs k] (if (seq cs) (str/join ", " (map #(str (:name %) (if (:ok %) " ✓" " ✗")) cs)) (str "none " k)))]
    (str "You are bba in world mode. You grow one live Clojure (babashka) namespace, `" ns-name "`, with no build step.\n"
         "Tools: develop_form {code, remove?} adds, redefines or removes definitions (defn, def, defonce). "
         "execute_form {code} only calls existing functions; it must not def anything. "
         "Available tools: " (str/join ", " (sort (map :name (ext/tools)))) ".\n"
         "Every attempt runs against a checkpoint. On any error or failed invariant the world is restored to the "
         "last accepted state and you get an EDN result with :status, :message, :failed or :form. Read it, fix with "
         "develop_form, and retry. This is not Common Lisp: there are no restarts. Results are EDN.\n"
         "Put side effects in execute_form, not develop_form (develop forms are replayed on restore). "
         "Do not use ns or in-ns. clojure.string is aliased as str.\n"
         "Invariants (must hold): " (status (or (:invariants checks) (map #(assoc % :ok false) (:invariants adapter))) "") "\n"
         "Goals: " (status (:goals checks) "") "\n"
         "Functions (" (count cat) "):"
         (str/join (for [e (take 60 cat)] (str "\n- " (:name e) (some->> (:arglists e) pr-str (str " ")) (some->> (first-line (:doc e)) (str " — ")))))
         (when (> (count cat) 60) (str "\n… " (- (count cat) 60) " more; ask the user to run /functions")))))

;; ---------------------------------------------------------------- commands

(defn- show-result [r]
  (case (:status r)
    :accepted (do (println (ui/green (str "accepted: revision " (:revision r)))
                           (ui/dim (str (:op r) " " (:ms r) " ms"
                                        (when (seq (:defined r)) (str " defined " (str/join " " (:defined r))))
                                        (when (seq (:removed r)) (str " removed " (str/join " " (:removed r)))))))
                  (when (:value r) (println "=>" (:value r)))
                  (when-let [g (seq (get-in r [:checks :goals]))]
                    (println (ui/dim (str "goals: " (str/join ", " (map #(str (:name %) (if (:ok %) " met" " unmet")) g))))))
                  (when (:note r) (println (ui/dim (:note r)))))
    :preview (println "=>" (:value r) (ui/dim "(preview: not kept)"))
    (println (ui/red (str (name (:status r)) ": " (or (:message r) (some-> (:reason r) name))
                          (when (:failed r) (str " " (str/join ", " (map :name (:failed r)))))))
             (ui/dim (pr-str (dissoc r :status :message :op :ms))))))

(defn- with-sigint
  "Run (f) on this thread; Ctrl-C interrupts it. The interrupted attempt restores itself."
  [f]
  (let [t (Thread/currentThread) sig (sun.misc.Signal. "INT")
        old (sun.misc.Signal/handle sig (reify sun.misc.SignalHandler (handle [_ _] (.interrupt t))))]
    (try (f)
         (catch InterruptedException _ (Thread/interrupted) (println (ui/yellow "(aborted)")) nil)
         (finally (sun.misc.Signal/handle sig old)))))

(defn- manual [op] (fn [args _] (with-sigint #(some-> (attempt! {:op op :code args}) show-result))))

(defn- develop-args [args]
  (if (re-matches #"\s*(-\S+\s*)+" args)
    {:remove (mapv #(subs % 1) (str/split (str/trim args) #"\s+")) :code ""}
    {:code args}))

(defn- history []
  (let [{:keys [dir rev]} @state]
    (if-let [nums (seq (store/rev-numbers dir))]
      (doseq [n nums :let [r (store/read-rev dir n)]]
        (println (str (if (= n rev) "* " "  ") "r" n "  " (subs (str (:op-id r) "        ") 0 8) "  "
                      (format "%-8s" (name (:type r))) "  " (:ts r) "  "
                      (let [s (str/replace (str (:input r)) #"\s+" " ")] (if (> (count s) 60) (str (subs s 0 60) "...") s)))))
      (println "no revisions yet"))))

(defn- status []
  (let [{:keys [ns-name rev base-rev checks restore-ms timeouts]} @state
        pass (fn [cs] (str (count (filter :ok cs)) "/" (count cs)))]
    (println (str "world: " ns-name ", revision " (or rev 0)
                  (when (not= rev base-rev) (str " (running from base revision " base-rev ")"))
                  ", " (count (catalogue)) " functions, invariants " (pass (:invariants checks))
                  " passing, goals " (pass (:goals checks)) " met"
                  (when restore-ms (str ", last restore " restore-ms " ms"))
                  (when (pos? (or timeouts 0)) (str ", " timeouts " timed-out thread(s) may still run; restart bba"))))))

(defn- describe [name]
  (if-let [e (some #(when (= (str (:name %)) (str/trim name)) %) (catalogue))]
    (do (println (ui/bold (str (:name e))) (ui/dim (str (clojure.core/name (:kind e)) ", revision " (:revision e))))
        (println "arguments:" (pr-str (:arglists e)))
        (println "doc:" (or (:doc e) "-"))
        (println (:source e)))
    (println (ui/red (str "unknown function: " name " (try /functions)")))))

(def ^:private tool-desc
  {"develop_form" "Add, redefine or remove definitions in the live world namespace. code: one or more Clojure forms (defn, def, defonce). remove: names to remove. All or nothing: any error or failed invariant restores the last accepted state. Returns EDN."
   "execute_form" "Call or compose existing world functions. code: one or more forms; the printed value of the last is returned. Must not def or redefine vars. Errors restore the last accepted state. Returns EDN."})

(defn- tool-result [r] {:content (pr-str r) :is-error (not (#{:accepted :preview} (:status r)))})

(defn register! []
  (binding [ext/*source* :builtin]
    (ext/register-tool! {:name "develop_form" :description (tool-desc "develop_form")
                         :input-schema {:type "object" :required ["code"]
                                        :properties {:code {:type "string"} :remove {:type "array" :items {:type "string"}}}}
                         :handler (fn [{:keys [code remove]} _] (tool-result (attempt! {:op :develop :code code :remove remove})))})
    (ext/register-tool! {:name "execute_form" :description (tool-desc "execute_form")
                         :input-schema {:type "object" :required ["code"] :properties {:code {:type "string"}}}
                         :handler (fn [{:keys [code]} _] (tool-result (attempt! {:op :execute :code code})))})
    (ext/register-command! "develop" (fn [args _] (with-sigint #(some-> (attempt! (assoc (develop-args args) :op :develop)) show-result))))
    (ext/register-command! "execute" (manual :execute))
    (ext/register-command! "preview" (manual :preview))
    (ext/register-command! "rollback" (fn [args _] (show-result (rollback! (parse-long (str/trim args))))))
    (ext/register-command! "abort" (fn [_ _] (println "nothing to abort (Ctrl-C stops a running operation)")))
    (ext/register-command! "functions" (fn [_ _] (let [c (catalogue)]
                                                   (if (empty? c) (println "no functions yet")
                                                       (doseq [e c] (println (str (:name e)) (ui/dim (or (first-line (:doc e)) "")))))) ))
    (ext/register-command! "describe" (fn [args _] (describe args)))
    (ext/register-command! "history" (fn [_ _] (history)))
    (ext/register-command! "status" (fn [_ _] (status)))))

;; ---------------------------------------------------------------- start / stop

(defn- load-from-disk!
  "Rebuild from CURRENT; on failure try lower revisions. Never changes history."
  [dir]
  (let [nums (store/rev-numbers dir)
        cur (or (store/current dir)
                (when (seq nums) (ext/warn "world CURRENT is missing or corrupt; using the highest revision") (last nums)))]
    (doseq [n nums :when (and cur (> n cur))] (ext/warn "ignored interrupted operation r" n " (above CURRENT r" cur ")"))
    (swap! state assoc :rev (or cur 0) :base-rev 0)
    (loop [[n & more] (reverse (filter #(<= % (or cur 0)) nums))]
      (if-not n
        (do (restore! {:log []})
            (when cur (ext/warn "no revision loads; the world is empty until /rollback")))
        (let [ok (try (let [r (store/read-rev dir n)]
                        (restore! r)
                        (swap! state assoc :snapshot {:log (:log r)} :base-rev n)
                        true)
                      (catch Exception e (ext/warn "world revision r" n " does not load: " (ex-message e)) false))]
          (cond ok (when (not= n cur) (ext/warn "world running from r" n "; CURRENT is r" cur))
                :else (recur more)))))))

(defn start!
  "Open the world in `cwd`: lock, clean temp files, rebuild from CURRENT, register tools
  and commands, check invariants. Throws ex-info when the world cannot be opened."
  [{:keys [cwd]}]
  (let [dir (store/world-dir cwd)
        created (store/ensure-dir! dir)
        adapter (try (store/read-adapter dir)
                     (catch Exception e (throw (ex-info (str "cannot read " dir "/world.edn: " (ex-message e)) {}))))
        pid (store/lock! dir)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(store/unlock! dir pid)))
    (doseq [t (store/delete-tmp! dir)] (ext/warn "deleted leftover temp file " t))
    (reset! state {:dir dir :adapter adapter :ns-name (:ns adapter) :pid pid :snapshot {:log []} :timeouts 0})
    (load-from-disk! dir)
    (let [w (wns) checks {:invariants (run-checks w (:invariants adapter)) :goals (run-checks w (:goals adapter))}]
      (swap! state assoc :checks checks)
      (doseq [c (:invariants checks) :when (not (:ok c))]
        (ext/warn "world invariant fails: " (:name c) (some->> (:error c) (str ": ")))))
    (register!)
    {:created created :rev (:rev @state) :ns (:ns adapter) :functions (count (catalogue))}))

(defn stop!
  "Release the lock (the namespace stays loaded)."
  []
  (when-let [{:keys [dir pid]} @state] (store/unlock! dir pid)))
