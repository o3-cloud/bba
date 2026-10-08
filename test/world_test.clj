(ns world-test
  "World mode (bba.world, bba.world-store). Offline: direct evaluator calls, or main/run with
  the fake provider or no provider at all. Every test works in a bba-test temp folder."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.main :as main]
            [bba.world :as world]
            [bba.world-store :as store]))

(def ^:private created (atom []))

(defn- temp-cwd []
  (let [d (str (fs/create-temp-dir {:prefix "bba-test"}))]
    (swap! created conj d)
    (let [cwd (str (fs/path d "work"))] (fs/create-dirs cwd) cwd)))

(use-fixtures :each (fn [t]
                      (ext/reset-all!)
                      (try (t)
                           (finally (world/stop!)
                                    (reset! world/state nil)
                                    (ext/reset-all!)
                                    (run! fs/delete-tree @created)
                                    (reset! created [])))))

;; ---------------------------------------------------------------- helpers

(defn- wdir [cwd] (store/world-dir cwd))

(defn- adapter! [cwd m]
  (fs/create-dirs (wdir cwd))
  (spit (str (fs/path (wdir cwd) "world.edn")) (pr-str (merge store/default-adapter m))))

(defn- start [cwd] (binding [*err* (java.io.StringWriter.)] (world/start! {:cwd cwd})))

(defn- start-err
  "Start the world and return what it printed on stderr."
  [cwd]
  (let [err (java.io.StringWriter.)]
    (binding [*err* err] (world/start! {:cwd cwd}))
    (str err)))

(defn- develop [code & {:keys [remove]}] (world/attempt! {:op :develop :code code :remove remove}))
(defn- execute [code] (world/attempt! {:op :execute :code code}))

(defn- call [sym & args] (apply @(ns-resolve 'world sym) args))
(defn- world-val [sym] @@(ns-resolve 'world sym))
(defn- revs [cwd] (store/rev-numbers (wdir cwd)))
(defn- cmd [name args] (with-out-str ((:handler (ext/command name)) args {})))

(defn- world-shape
  "Public names plus state contents: what a restore must preserve."
  []
  {:publics (set (keys (ns-publics 'world))) :state (#'world/live-state)})

(def rev-src "(defn reverse-string \"Reverse s.\" [s] (apply str (reverse s)))")
(def up-src "(defn uppercase-string \"Upper-case s.\" [s] (str/upper-case s))")

(defn- fake-provider [script]
  (let [calls (atom []) left (atom script)]
    {:calls calls
     :fn (fn [req]
           (swap! calls conj req)
           (let [c (or (first @left) [{:type "text" :text "(end)"}])]
             (swap! left rest)
             {:role "assistant" :content c}))}))

(defn- run-bba [cwd args & {:keys [input provider env]}]
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)
        code (binding [*out* out *err* err]
               (when provider (ext/set-provider! (:fn provider)))
               (main/run {:args args :cwd cwd
                          :env (merge {"BBA_HOME" (str (fs/path (fs/parent cwd) "home"))} env)
                          :in (java.io.BufferedReader. (java.io.StringReader. (or input "")))}))]
    {:code code :out (str out) :err (str err)}))

;; ---------------------------------------------------------------- contract scenarios

(deftest s1-develop-and-catalogue
  (let [cwd (temp-cwd) _ (start cwd) r (develop rev-src)]
    (is (= :accepted (:status r)))
    (is (= "cba" (call 'reverse-string "abc")) "function exists in the world namespace")
    (let [d (cmd "describe" "reverse-string")]
      (is (str/includes? d "([s])"))
      (is (str/includes? d "Reverse s."))
      (is (str/includes? d rev-src)))
    (is (= [1] (revs cwd)))
    (is (= 1 (store/current (wdir cwd))) "CURRENT points to the new revision")
    (is (= "develop" (:op r)))
    (is (string? (:op-id (store/read-rev (wdir cwd) 1))))
    (is (str/includes? (cmd "describe" "nope") "unknown function: nope"))))

(deftest s2-execute-composes-without-new-code
  (let [cwd (temp-cwd) _ (start cwd)
        _ (develop (str rev-src "\n" up-src))
        before (world/catalogue)
        r (execute "(reverse-string (uppercase-string \"Hello\"))")]
    (is (= :accepted (:status r)))
    (is (= "\"OLLEH\"" (:value r)))
    (is (= before (world/catalogue)) "no new function")
    (is (= [] (:defined r)))
    (is (= (:log (store/read-rev (wdir cwd) 1)) (:log (store/read-rev (wdir cwd) 2))) "log unchanged")))

(deftest s3-world-persists-across-sessions
  (let [cwd (temp-cwd)
        r1 (run-bba cwd ["--world"] :input (str "/develop " rev-src "\n"))
        _ (remove-ns 'world)
        _ (ext/reset-all!)
        r2 (run-bba cwd ["--world"] :input "/functions\n/execute (reverse-string \"abc\")\n")]
    (is (= 0 (:code r1)))
    (is (= 0 (:code r2)))
    (is (str/includes? (:out r2) "world: main, revision 1, 1 functions"))
    (is (str/includes? (:out r2) "reverse-string"))
    (is (str/includes? (:out r2) "=> \"cba\""))
    (is (not (fs/exists? (fs/path (wdir cwd) "LOCK"))) "lock released on exit")))

(deftest s4-failing-invariant-rejects
  (let [cwd (temp-cwd)
        _ (adapter! cwd {:invariants [{:name "rev-abc" :form '(= "cba" (reverse-string "abc"))}]})
        err (start-err cwd)]
    (is (str/includes? err "world invariant fails: rev-abc") "checked once at start-up")
    (is (= :accepted (:status (develop rev-src))))
    (let [r (develop "(defn reverse-string [s] s)")]
      (is (= :rejected (:status r)))
      (is (= :invariant (:reason r)))
      (is (= ["rev-abc"] (map :name (:failed r))) "failed invariant named")
      (is (= 1 (:restored-to r))))
    (is (= "cba" (call 'reverse-string "abc")) "previous definition still works")
    (is (= [1] (revs cwd)) "no new revision")))

(deftest s5-exception-restores-checkpoint
  (let [cwd (temp-cwd) _ (start cwd)
        _ (develop "(defonce counter (atom 0))")
        before (world-shape)
        r (execute "(swap! counter inc) (throw (ex-info \"boom\" {:k 1}))")]
    (is (= :error (:status r)))
    (is (= "boom" (:message r)))
    (is (= "{:k 1}" (:data r)))
    (is (= 1 (:form-index r)))
    (is (str/includes? (:form r) "throw"))
    (is (= before (world-shape)))
    (is (= 0 (world-val 'counter)))
    (is (= [1] (revs cwd)))))

(deftest s6-preview-does-not-keep-effects
  (let [cwd (temp-cwd) _ (start cwd)
        _ (develop "(defonce counter (atom 0))")
        out (cmd "preview" "(swap! counter inc)")]
    (is (str/includes? out "=> 1"))
    (is (= 0 (world-val 'counter)))
    (is (= [1] (revs cwd)) "preview writes no revision")
    (is (= :preview (:status (world/attempt! {:op :preview :code "(def tmp 1)"}))))
    (is (nil? (ns-resolve 'world 'tmp)))))

(deftest s7-rollback-keeps-history
  (let [cwd (temp-cwd) _ (start cwd)]
    (develop rev-src) (develop up-src) (develop "(defn three [] 3)")
    (let [r (world/rollback! 1)]
      (is (= :accepted (:status r)))
      (is (= 4 (:revision r))))
    (let [r4 (store/read-rev (wdir cwd) 4)]
      (is (= :rollback (:type r4)))
      (is (= 1 (:rollback-of r4)))
      (is (= (:log (store/read-rev (wdir cwd) 1)) (:log r4))))
    (is (= [1 2 3 4] (revs cwd)))
    (is (nil? (ns-resolve 'world 'three)) "world equals revision 1")
    (let [h (cmd "history" "")]
      (is (str/includes? h "r2"))
      (is (str/includes? h "r3"))
      (is (str/includes? h "* r4")))
    (testing "unknown N: error, nothing changes"
      (let [r (world/rollback! 99)]
        (is (= :error (:status r)))
        (is (str/includes? (:message r) "unknown revision: 99"))
        (is (= [1 2 3 4] (revs cwd)))))))

(deftest s8-manual-commands-without-key
  (let [cwd (temp-cwd)
        {:keys [code out err]} (run-bba cwd ["--world"]
                                        :input (str "/develop " rev-src "\n/execute (reverse-string \"xy\")\n/status\n"))]
    (is (= 0 code))
    (is (str/includes? err "ANTHROPIC_API_KEY is not set; manual /commands only"))
    (is (str/includes? out "accepted: revision 1"))
    (is (str/includes? out "=> \"yx\""))
    (is (str/includes? out "revision 2"))
    (is (= [1 2] (revs cwd))))
  (testing "-p --world without a key still exits 1"
    (let [cwd (temp-cwd)]
      (is (= 1 (:code (run-bba cwd ["--world" "-p" "hi"]))))))
  (testing "the world is the only mode: no flag needed, manual commands still work"
    (let [cwd (temp-cwd)
          {:keys [code out]} (run-bba cwd [] :input (str "/develop " rev-src "\n/execute (reverse-string \"ab\")\n"))]
      (is (= 0 code))
      (is (str/includes? out "=> \"ba\"")))))

(deftest s9-half-written-revision-never-current
  (let [cwd (temp-cwd) dir (wdir cwd) _ (start cwd)]
    (develop rev-src) (develop up-src)
    (world/stop!)
    ;; crash between the revision write and the CURRENT move, plus leftover temp files
    (spit (str (fs/path dir "revisions" "000003.edn"))
          (pr-str {:rev 3 :type :develop :log [{:source "(defn orphan [] 1)" :defines 'orphan :kind :fn :rev 3}] :state {}}))
    (spit (str (fs/path dir "revisions" ".000004.edn.tmp")) "{:rev 4 :lo")
    (spit (str (fs/path dir ".CURRENT.tmp")) "0000")
    (remove-ns 'world)
    (let [err (start-err cwd)]
      (is (str/includes? err "ignored interrupted operation r3"))
      (is (str/includes? err "deleted leftover temp file")))
    (is (= 2 (store/current dir)))
    (is (= 2 (:rev @world/state)))
    (is (nil? (ns-resolve 'world 'orphan)) "interrupted operation never replayed")
    (is (some? (ns-resolve 'world 'uppercase-string)))
    (is (empty? (fs/glob dir "**.tmp" {:hidden true})))
    (is (= 4 (:revision (develop "(defn four [] 4)"))) "orphan number never reused")))

(deftest s10-large-results-are-bounded
  (let [cwd (temp-cwd) _ (start cwd)
        r (execute "(apply str (repeat 10000 \"x\"))")]
    (is (= :accepted (:status r)))
    (is (< (count (:value r)) 4100))
    (is (str/includes? (:value r) "[truncated: 10002 chars]")))
  (testing "infinite sequences print with *print-length*"
    (is (str/ends-with? (:value (execute "(range)")) "...)"))))

;; ---------------------------------------------------------------- tool path (fake provider)

(defn- tool-use [id name input] {:type "tool_use" :id id :name name :input input})

(deftest tools-through-the-agent-loop
  (let [cwd (temp-cwd)
        fp (fake-provider [[(tool-use "t1" "develop_form" {:code "(defn reverse-string [s] (apply str (reverse s))) (/ 1 0)"})]
                           [(tool-use "t2" "develop_form" {:code rev-src})]
                           [(tool-use "t3" "execute_form" {:code "(reverse-string \"abc\")"})]
                           [{:type "text" :text "done"}]])
        {:keys [code out]} (run-bba cwd ["--world" "--no-extensions" "-p" "grow it"] :provider fp)
        calls @(:calls fp)
        results (fn [i] (->> (nth calls i) :messages last :content (filter #(= "tool_result" (:type %))) first))]
    (is (= 0 code))
    (is (= "done" (str/trim out)) "-p output is only the answer")
    (is (= #{"develop_form" "execute_form" "activate_skill"} (set (map :name (:tools (first calls))))) "the two world tools plus the core skills tool")
    (is (str/includes? (:system (first calls)) "The world is your only tool"))
    (let [r1 (edn/read-string (:content (results 1)))]
      (is (true? (:is_error (results 1))))
      (is (= "Divide by zero" (:message r1)) "error returned to the model")
      (is (= 0 (:restored-to r1))))
    (is (= :accepted (:status (edn/read-string (:content (results 2))))))
    (is (= "\"cba\"" (:value (edn/read-string (:content (results 3))))))
    (is (str/includes? (:system (last calls)) "reverse-string ([s]) — Reverse s.") "catalogue in the prompt")
    (is (= [1 2] (revs cwd)))))

;; ---------------------------------------------------------------- edges and risks

(deftest goals-reported-not-enforced
  (let [cwd (temp-cwd)
        _ (adapter! cwd {:goals [{:name "has-upper" :form '(ifn? uppercase-string)}]})
        _ (start cwd)
        r (develop rev-src)]
    (is (= :accepted (:status r)))
    (is (= [{:name "has-upper" :ok false :error "Unable to resolve symbol: uppercase-string"}]
           (get-in r [:checks :goals])))
    (is (= [{:name "has-upper" :ok true}] (get-in (develop up-src) [:checks :goals])))))

(deftest execute-must-not-define-or-redefine
  (let [cwd (temp-cwd) _ (start cwd) _ (develop rev-src)]
    (let [r (execute "(def sneaky 1)")]
      (is (= [:rejected :new-var '[sneaky]] [(:status r) (:reason r) (:vars r)])))
    (let [r (execute "(defn reverse-string [s] s)")]
      (is (= [:rejected :redefined '[reverse-string]] [(:status r) (:reason r) (:vars r)])))
    (is (nil? (ns-resolve 'world 'sneaky)))
    (is (= "cba" (call 'reverse-string "abc")))
    (is (= [1] (revs cwd)))))

(deftest reader-errors-and-namespace-switches
  (let [cwd (temp-cwd) _ (start cwd)]
    (let [r (develop "(defn broken [")]
      (is (= "reader" (:class r)))
      (is (str/includes? (:message r) "line 1")))
    (is (= "reader" (:class (develop "(in-ns 'other)"))))
    (is (= "nothing to evaluate" (:message (develop "  ;; just a comment"))))
    (is (empty? (revs cwd)))))

(deftest multi-form-develop-is-all-or-nothing
  (let [cwd (temp-cwd) _ (start cwd)
        r (develop "(defn a [] 1) (def b (Integer/parseInt \"x\"))")]
    (is (= :error (:status r)))
    (is (= 1 (:form-index r)))
    (is (nil? (ns-resolve 'world 'a)) "first form rolled back")
    (is (empty? (revs cwd)))))

(deftest lint-rejects-errors-before-evaluation
  (let [cwd (temp-cwd) _ (start cwd) _ (develop "(defn add [a b] (+ a b))")]
    (testing "unresolved symbol: rejected, nothing evaluated, no revision"
      (let [r (develop "(def side (atom :ran))\n(defn f [] (nope))")]
        (is (= :rejected (:status r)))
        (is (= :lint (:reason r)))
        (is (= [{:row 2 :type :unresolved-symbol}] (map #(select-keys % [:row :type]) (:lint r))))
        (is (nil? (ns-resolve 'world 'side)) "the first form never ran")
        (is (= [1] (revs cwd)))))
    (testing "arity is checked against world functions"
      (let [r (execute "(add 1)")]
        (is (= :lint (:reason r)))
        (is (= :invalid-arity (-> r :lint first :type)))))
    (testing "warnings are reported on accepted results"
      (let [r (develop "(defn g [x] (let [y 1] x))")]
        (is (= :accepted (:status r)))
        (is (= [:unused-binding] (map :type (:lint r))))))
    (testing "world aliases resolve"
      (is (nil? (:lint (execute "(str/upper-case (fs/file-name \"a/b\"))")))))))

(deftest library-aliases-survive-restores
  (let [cwd (temp-cwd) _ (start cwd)
        code "[(json/generate-string {:a 1}) (count (csv/read-csv \"a,b\")) (edn/read-string \"1\")
               (some? http/get) (some? yaml/parse-string) (some? io/file) (some? walk/keywordize-keys)
               (some? pp/pprint) (some? proc/process)]"
        expected "[\"{\\\"a\\\":1}\" 1 1 true true true true true true]"]
    (let [r (execute code)]
      (is (= :accepted (:status r)))
      (is (= expected (:value r)))
      (is (nil? (:lint r)) "clj-kondo knows every alias"))
    (is (= :error (:status (execute "(throw (ex-info \"boom\" {}))"))) "forces a restore")
    (is (= expected (:value (execute code))) "aliases are back after the restore")))

(deftest lint-warn-and-off
  (doseq [mode [:warn :off]]
    (let [cwd (temp-cwd) _ (adapter! cwd {:lint mode}) _ (start cwd)
          r (develop "(defn f [] (nope))")]
      (is (= :error (:status r)) (str mode ": the code is evaluated"))
      (is (= (= mode :warn) (boolean (seq (:lint r)))) (str mode ": findings reported only for :warn"))
      (world/stop!))))

(deftest removal
  (let [cwd (temp-cwd) _ (start cwd) _ (develop (str rev-src up-src))]
    (cmd "develop" "-uppercase-string")
    (is (nil? (ns-resolve 'world 'uppercase-string)))
    (is (= ["reverse-string"] (map (comp str :name) (world/catalogue))) "removed functions leave the catalogue")
    (is (= "remove" (:class (develop "" :remove ["nope"]))))
    (world/stop!) (remove-ns 'world) (start cwd)
    (is (nil? (ns-resolve 'world 'uppercase-string)) "removal replays")))

(deftest timeout-restores
  ;; Blocking code: future-cancel stops it (P6). A CPU loop cannot be stopped (P7); it is
  ;; detached by the rebuild, and this test does not start one (it would burn CPU until exit).
  (let [cwd (temp-cwd) _ (adapter! cwd {:timeout-ms 200}) _ (start cwd)
        _ (develop "(defonce counter (atom 0))")
        r (execute "(reset! counter 5) (Thread/sleep 5000)")]
    (is (= "timeout" (:class r)))
    (is (str/includes? (:message r) "runaway thread"))
    (is (= 0 (world-val 'counter)))
    (is (str/includes? (cmd "status" "") "1 timed-out thread(s)"))))

(deftest lock-held-by-live-process
  (let [cwd (temp-cwd) dir (wdir cwd) _ (store/ensure-dir! dir)
        proc (p/process ["sleep" "30"])]
    (try
      (spit (str (fs/path dir "LOCK")) (str (.pid (:proc proc))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"world in use by pid" (start cwd)))
      (testing "main exits 1 with the message"
        (let [r (run-bba cwd ["--world"])]
          (is (= 1 (:code r)))
          (is (str/includes? (:err r) "world in use by pid"))))
      (finally (p/destroy proc) @proc))
    (testing "a stale lock is taken over"
      (spit (str (fs/path dir "LOCK")) "999999")
      (is (str/includes? (start-err cwd) "taking over stale world lock from pid 999999")))))

(deftest startup-falls-back-when-latest-does-not-load
  (let [cwd (temp-cwd) dir (wdir cwd) _ (start cwd)]
    (develop rev-src) (develop up-src)
    (world/stop!) (remove-ns 'world)
    (spit (str (fs/path dir "revisions" "000002.edn")) "{:rev 2 :log [{:source \"(defn x [\"}]}")
    (let [err (start-err cwd)]
      (is (str/includes? err "world revision r2 does not load"))
      (is (str/includes? err "world running from r1")))
    (is (= 2 (store/current dir)) "history untouched")
    (is (some? (ns-resolve 'world 'reverse-string)))
    (is (str/includes? (cmd "status" "") "running from base revision 1"))
    (testing "missing CURRENT: highest revision is used"
      (world/stop!) (remove-ns 'world)
      (fs/delete (fs/path dir "CURRENT"))
      (fs/delete (fs/path dir "revisions" "000002.edn"))
      (is (str/includes? (start-err cwd) "CURRENT is missing or corrupt"))
      (is (= 1 (:rev @world/state))))))

(deftest develop-side-effects-replay-on-restore
  ;; R2: pinned, not hidden. Each restore replays the log, so a top-level println re-runs.
  (let [cwd (temp-cwd) _ (start cwd)
        out1 (with-out-str (develop "(println \"SIDE\")"))
        out2 (with-out-str (execute "(throw (ex-info \"x\" {}))"))]
    (is (= 1 (count (re-seq #"SIDE" out1))))
    (is (= 1 (count (re-seq #"SIDE" out2))) "replayed once by the restore")))

(deftest gitignore-and-default-adapter
  (let [cwd (temp-cwd) info (start cwd) dir (wdir cwd)]
    (is (:created info))
    (is (= 'world (:ns (store/read-adapter dir))))
    (is (str/includes? (slurp (str (fs/path dir ".gitignore"))) "revisions/"))
    (is (not (str/includes? (slurp (str (fs/path dir ".gitignore"))) "world.edn")) "adapter is committable")
    (is (= 1 (count (str/split-lines (do (develop rev-src) (slurp (str (fs/path dir "ops.jsonl")))))))
        "one ops line per attempt")))

(deftest adapter-ns-must-not-name-a-harness-namespace
  ;; gate 6 S1: restore runs remove-ns on the world ns, so a harness/library name would wipe it.
  (doseq [bad ['bba.ext 'bba.core 'clojure.core 'clojure.string 'user 'babashka.fs "world" 'a/b]]
    (let [cwd (temp-cwd)]
      (adapter! cwd {:ns bad})
      (is (thrown? Exception (start cwd)) (str "rejected " (pr-str bad)))))
  (is (some? (resolve 'bba.ext/tools)) "harness namespace still loaded")
  (doseq [ok ['world 'my.app 'bbapp]]
    (let [cwd (temp-cwd)]
      (adapter! cwd {:ns ok})
      (is (= ok (:ns (start cwd)))))))

(deftest restore-property-random-sequences
  ;; R4: for any sequence of good / throwing / invariant-breaking / mutating attempts,
  ;; a failed attempt changes nothing, and memory equals a fresh rebuild from CURRENT.
  (let [cwd (temp-cwd)
        _ (adapter! cwd {:invariants [{:name "n-non-negative" :form '(or (not (resolve 'n)) (>= @n 0))}]})
        _ (start cwd)
        _ (develop "(defonce n (atom 0))")
        rnd (java.util.Random. 42)
        attempts [#(develop (str "(defn f" (.nextInt rnd 5) " [] " (.nextInt rnd 100) ")"))
                  #(develop (str "(defn g" (.nextInt rnd 5) " [] 1) (throw (ex-info \"no\" {}))"))
                  #(execute "(reset! n -1)")
                  #(execute (str "(swap! n + " (.nextInt rnd 3) ")"))
                  #(execute "(swap! n inc) (/ 1 0)")
                  #(execute (str "(def leak" (.nextInt rnd 3) " 1)"))]]
    (dotimes [_ 60]
      (let [before (world-shape)
            r ((nth attempts (.nextInt rnd (count attempts))))]
        (when-not (= :accepted (:status r))
          (is (= before (world-shape)) (str "failed attempt changed the world: " (pr-str r))))))
    (let [live (world-shape) rev (:rev @world/state)]
      (world/stop!) (remove-ns 'world) (start cwd)
      (is (= rev (:rev @world/state)))
      (is (= live (world-shape)) "memory equals a rebuild from CURRENT"))))

(deftest ctrl-c-interrupt-restores
  ;; The Ctrl-C path: main/with-cancel interrupts the thread that waits in attempt!.
  (let [cwd (temp-cwd) _ (start cwd)
        _ (develop "(defonce counter (atom 0))")
        outer (future (execute "(reset! counter 5) (Thread/sleep 5000)"))]
    (Thread/sleep 300)
    (future-cancel outer)
    (Thread/sleep 300)
    (is (= 0 (world-val 'counter)))
    (is (= [1] (revs cwd)))
    (is (str/includes? (slurp (str (fs/path (wdir cwd) "ops.jsonl"))) "\"outcome\":\"aborted\""))))

;; ---------------------------------------------------------------- named worlds

(defn- wcmd [args] (binding [*out* (java.io.StringWriter.)] (world/world-command args)))
(defn- defined? [sym] (some? (ns-resolve 'world sym)))
(defn- lock-file [cwd name] (fs/path (store/world-dir cwd name) "LOCK"))

(deftest old-single-world-moves-to-main
  (let [cwd (temp-cwd) _ (start cwd) _ (develop rev-src)
        old (fs/path cwd ".bba" "world")]
    (world/stop!) (remove-ns 'world)
    (fs/move (wdir cwd) old)                      ; the layout before named worlds
    (fs/delete-tree (store/worlds-dir cwd))
    (let [err (start-err cwd)]
      (is (str/includes? err "moved"))
      (is (not (fs/exists? old)) "old folder moved, not copied")
      (is (= "main" (world/current-name)))
      (is (= "cba" (call 'reverse-string "abc")) "history came along")
      (is (= [1] (revs cwd))))
    (testing "both layouts: keep worlds/main and leave the old folder alone"
      (world/stop!)
      (fs/create-dirs old) (spit (str (fs/path old "world.edn")) "{}")
      (is (str/includes? (start-err cwd) "leaving the old folder alone"))
      (is (fs/exists? old))
      (is (defined? 'reverse-string)))))

(deftest create-switch-and-isolate
  (let [cwd (temp-cwd) _ (start cwd) _ (develop rev-src)]
    (is (nil? (wcmd "new jobs")) "new does not switch")
    (is (= "main" (world/current-name)))
    (is (= ["jobs" "main"] (store/world-names cwd)))
    (let [i (wcmd "jobs")]
      (is (= {:name "jobs" :rev 0 :functions 0} (select-keys i [:name :rev :functions]))))
    (is (not (defined? 'reverse-string)) "main's functions are gone")
    (is (= "jobs" (store/active cwd)))
    (is (fs/exists? (lock-file cwd "jobs")))
    (is (not (fs/exists? (lock-file cwd "main"))) "the old world's lock is released")
    (develop "(defn only-jobs [] :jobs)")
    (is (= [1] (store/rev-numbers (store/world-dir cwd "jobs"))))
    (is (= [1] (revs cwd)) "main's history untouched")
    (is (str/includes? (world/system-prompt {}) "This is world \"jobs\""))
    (wcmd "main")
    (is (defined? 'reverse-string))
    (is (not (defined? 'only-jobs)))
    (is (str/includes? (world/switch-note (#'world/info false)) "reverse-string"))
    (testing "next start opens the last active world"
      (wcmd "jobs") (world/stop!) (remove-ns 'world)
      (start cwd)
      (is (= "jobs" (world/current-name)))
      (is (defined? 'only-jobs)))))

(deftest fork-at-a-revision
  (let [cwd (temp-cwd) _ (start cwd)]
    (develop rev-src) (develop up-src)
    (wcmd "fork old 1")
    (wcmd "fork now")
    (is (str/includes? (with-out-str (world/world-command "fork bad 99")) "unknown revision: 99"))
    (is (not (store/exists? cwd "bad")) "a failed fork leaves nothing behind")
    (let [r1 (store/read-rev (store/world-dir cwd "old") 1)]
      (is (= {:world "main" :rev 1} (:forked-from r1)))
      (is (= :fork (:type r1))))
    (wcmd "old")
    (is (defined? 'reverse-string))
    (is (not (defined? 'uppercase-string)) "fork at r1 has only r1's functions")
    (is (= 2 (:revision (develop "(defn x [] 1)"))) "the fork continues its own numbering")
    (wcmd "now")
    (is (every? defined? '[reverse-string uppercase-string]) "default forks the current revision")
    (is (str/includes? (with-out-str (world/world-command "")) "forked from main r1"))))

(deftest failed-switch-keeps-the-current-world
  (let [cwd (temp-cwd) _ (start cwd) _ (develop rev-src)]
    (wcmd "new busy") (wcmd "new broken")
    (spit (str (fs/path (store/world-dir cwd "broken") "world.edn")) "{:ns clojure.core}")
    (let [proc (p/process ["sleep" "30"])]
      (try
        (spit (str (lock-file cwd "busy")) (str (.pid (:proc proc))))
        (doseq [[target msg] [["busy" "world in use by pid"] ["broken" "is not allowed"]
                              ["nope" "no world nope"] ["main" "already in world main"]]]
          (let [out (with-out-str (world/world-command target))]
            (is (str/includes? out msg) target)
            (is (= "main" (world/current-name)))
            (is (= "cba" (call 'reverse-string "abc")))
            (is (fs/exists? (lock-file cwd "main")) "main still locked by us")))
        (finally (p/destroy proc) @proc)))))

(deftest world-names
  (doseq [ok ["main" "jobs" "a1" "x-2-y"]] (is (store/valid-name? ok) ok))
  (doseq [bad ["" "Jobs" "a--b" "-a" "a-" "../x" "a/b" "new" "fork" (apply str (repeat 65 "a"))]]
    (is (not (store/valid-name? bad)) bad))
  (let [cwd (temp-cwd) _ (start cwd)]
    (is (str/includes? (with-out-str (world/world-command "new Bad")) "bad world name"))
    (is (str/includes? (with-out-str (world/world-command "new a b c")) "usage"))
    (is (= ["main"] (store/world-names cwd)))))

(deftest cli-world-flag-and-continue
  (is (= {:world "jobs"} (main/parse-args ["--world" "jobs"])))
  (is (:warn (main/parse-args ["--world"])) "the old bare flag warns")
  (let [cwd (temp-cwd)
        fresh! #(do (remove-ns 'world) (ext/reset-all!) (reset! world/state nil))
        fp (fake-provider [[{:type "text" :text "ok"}]])
        r1 (run-bba cwd [] :provider fp :input "/world new jobs\n/world jobs\nhello\n/quit\n")
        sent (->> @(:calls fp) first :messages last :content first :text)]
    (is (= 0 (:code r1)))
    (is (str/starts-with? sent "[bba: the world is now \"jobs\""))
    (is (str/ends-with? sent "hello"))
    (is (= "jobs" (core/session-world (core/latest-session cwd))))
    (fresh!)
    (store/set-active! cwd "main")
    (let [r2 (run-bba cwd ["-c"] :input "/status\n")]
      (is (str/includes? (:out r2) "world: jobs") "-c resumes in the session's world, not ACTIVE"))
    (fresh!)
    (let [r3 (run-bba cwd ["-c" "--world" "main"] :input "/status\n")]
      (is (str/includes? (:out r3) "world: main") "--world wins over the session"))
    (fresh!)
    (let [r4 (run-bba cwd ["--world" "nope"])]
      (is (= 1 (:code r4)))
      (is (str/includes? (:err r4) "no world \"nope\"")))))
