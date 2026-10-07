(ns bba-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.main :as main]
            [bba.provider :as provider]
            [bba.sys :as sys]))

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def bb-bin (or (some-> (fs/which "bb") str) "bb"))

;; ---------------------------------------------------------------- helpers

(defn fake-provider
  "Returns {:fn provider :calls atom}. Replies come from `script` in order;
  each element is a vector of content blocks. Extra calls reply \"(end)\"."
  [script]
  (let [calls (atom []) left (atom script)]
    {:calls calls
     :fn (fn [req]
           (swap! calls conj req)
           (let [c (or (first @left) [{:type "text" :text "(end)"}])]
             (swap! left rest)
             {:role "assistant" :content c
              :stop_reason (if (some #(= "tool_use" (:type %)) c) "tool_use" "end_turn")}))}))

(defn tool-use [id name input] {:type "tool_use" :id id :name name :input input})

(defn text [s] {:type "text" :text s})

(defn tool-names [req] (set (map :name (:tools req))))

(defn tool-results [req]
  (->> (:messages req) last :content (filter #(= "tool_result" (:type %)))))

(def ^:private created (atom []))  ; temp folders, deleted after each test

(defn temp-dirs []
  (let [d (str (fs/create-temp-dir {:prefix "bba-test"}))]
    (swap! created conj d)
    {:cwd (str (fs/path d "work")) :home (str (fs/path d "home")) :base d}))

(defn copy-ext [cwd name]
  (let [dir (fs/path cwd ".bba" "extensions")]
    (fs/create-dirs dir)
    (fs/copy (fs/path root "extensions" name) (fs/path dir name) {:replace-existing true})))

(defn run-bba
  "Run main/run in-process with a fake provider installed by a project extension-free
  hook: the provider is set before run via a :builtin registration."
  [{:keys [cwd home]} provider args & {:keys [input env]}]
  (fs/create-dirs cwd)
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)
        code (binding [*out* out *err* err]
               (ext/set-provider! (:fn provider))
               (main/run {:args args :cwd cwd
                          :env (merge {"BBA_HOME" home} env)
                          :in (java.io.BufferedReader. (java.io.StringReader. (or input "")))}))]
    {:code code :out (str out) :err (str err)}))

(use-fixtures :each (fn [t]
                      (ext/reset-all!)
                      (try (t)
                           (finally (ext/reset-all!)
                                    (run! fs/delete-tree @created)
                                    (reset! created [])))))

;; ---------------------------------------------------------------- acceptance

(deftest ac1-print-mode-with-tool-use
  (let [dirs (temp-dirs)
        fp (fake-provider [[(tool-use "t1" "execute_form" {:code "(sys/sh \"echo hi\")"})] [(text "done")]])
        {:keys [code out]} (run-bba dirs fp ["-p" "say hi"])
        calls @(:calls fp)]
    (is (= 0 code))
    (is (= "done" (str/trim out)))
    (is (= 2 (count calls)))
    (is (= #{"develop_form" "execute_form"} (tool-names (first calls))) "the world tools are the only tools")
    (let [[r] (tool-results (second calls))]
      (is (= "t1" (:tool_use_id r)))
      (is (str/includes? (:content r) "hi"))
      (is (not (:is_error r))))))

(deftest ac2-extension-adds-tool
  (let [dirs (temp-dirs)
        _ (copy-ext (:cwd dirs) "reverse.clj")
        fp (fake-provider [[(tool-use "t1" "reverse" {:text "abc"})] [(text "ok")]])
        {:keys [code]} (run-bba dirs fp ["-p" "reverse abc"])
        [c1 c2] @(:calls fp)]
    (is (= 0 code))
    (is (contains? (tool-names c1) "reverse"))
    (is (= "cba" (:content (first (tool-results c2)))))))

(def reverse-src (slurp (str (fs/path root "extensions" "reverse.clj"))))

(def hello-cmd-src
  "(ns bba.extensions.hello (:require [bba.ext :as ext]))
   (ext/register-command! \"hello\" (fn [args _] (println (str \"hello \" args))))")

(deftest ac3-self-improvement-with-reload
  (let [dirs (temp-dirs)
        ext-dir (str (fs/path (:cwd dirs) ".bba" "extensions"))
        write-ext (fn [f src] (pr-str (list 'do (list 'fs/create-dirs ext-dir) (list 'spit (str ext-dir "/" f) src))))
        fp (fake-provider [[(tool-use "w1" "execute_form" {:code (write-ext "reverse.clj" reverse-src)})
                            (tool-use "w2" "execute_form" {:code (write-ext "hello.clj" hello-cmd-src)})]
                           [(text "written")]
                           [(text "now I have reverse")]])
        {:keys [code out]} (run-bba dirs fp [] :input "add a reverse tool\n/hello world\n/reload\n/hello world\nuse it\n/quit\n")
        [c1 _ c3] @(:calls fp)]
    (is (= 0 code))
    (is (not (contains? (tool-names c1) "reverse")) "no reverse before reload")
    (is (str/includes? out "unknown command: /hello") "command absent before reload")
    (is (contains? (tool-names c3) "reverse") "reverse visible after /reload")
    (is (str/includes? out "hello world") "new command works after /reload")
    (is (str/includes? out "now I have reverse"))))

(deftest ac4-hook-blocks-tool-call
  (let [dirs (temp-dirs)
        sentinel (str (fs/path (:base dirs) "x"))
        _ (fs/create-dirs sentinel)
        _ (copy-ext (:cwd dirs) "block_rm_rf.clj")
        fp (fake-provider [[(tool-use "b1" "execute_form" {:code (str "(sys/sh \"rm -rf " sentinel "\")")})] [(text "ok")]])
        {:keys [code]} (run-bba dirs fp ["-p" "delete it"])
        [r] (tool-results (second @(:calls fp)))]
    (is (= 0 code))
    (is (fs/exists? sentinel) "command did not run")
    (is (:is_error r))
    (is (str/includes? (:content r) "rm -rf is not allowed"))))

(deftest ac5-broken-extension-does-not-stop-harness
  (let [dirs (temp-dirs)
        dir (fs/path (:cwd dirs) ".bba" "extensions")
        _ (fs/create-dirs dir)
        _ (spit (str (fs/path dir "a_broken.clj")) "(ns broken (:require [bba.ext :as ext])) (ext/register-tool! {:name \"x\"")
        _ (copy-ext (:cwd dirs) "reverse.clj")
        fp (fake-provider [[(text "fine")]])
        {:keys [code err out]} (run-bba dirs fp ["-p" "hi"])]
    (is (= 0 code))
    (is (str/includes? err "a_broken.clj"))
    (is (str/includes? err "failed to load"))
    (is (contains? (tool-names (first @(:calls fp))) "reverse"))
    (is (= "fine" (str/trim out)))))

(deftest ac6-missing-api-key
  (testing "in-process: no provider installed, no key"
    (let [dirs (temp-dirs)
          err (java.io.StringWriter.)
          code (binding [*err* err *out* (java.io.StringWriter.)]
                 (main/run {:args ["-p" "hi"] :cwd (do (fs/create-dirs (:cwd dirs)) (:cwd dirs))
                            :env {"BBA_HOME" (:home dirs)} :in nil}))]
      (is (= 1 code))
      (is (str/includes? (str err) "ANTHROPIC_API_KEY is not set"))
      (is (not (fs/exists? (fs/path (:cwd dirs) ".bba" "sessions"))) "stopped before any run")))
  (testing "subprocess: bin/bba from another folder (also AC-9: entry point is bba)"
    (let [dirs (temp-dirs)
          _ (fs/create-dirs (:cwd dirs))
          env (-> (into {} (System/getenv)) (dissoc "ANTHROPIC_API_KEY") (assoc "BBA_HOME" (:home dirs)))
          r @(p/process {:dir (:cwd dirs) :out :string :err :string :extra-env {} :env env}
                        bb-bin (str (fs/path root "bin" "bba")) "-p" "hi")]
      (is (= 1 (:exit r)))
      (is (str/includes? (:err r) "ANTHROPIC_API_KEY is not set")))))

;; ---------------------------------------------------------------- derived ACs / units

;; NFR: core < 500 lines. Since 2026-10-07 "core" means the agent kernel (loop, extension API,
;; built-in tools), as in pi. Providers (provider, openai) and the terminal UI (main, tui, ui)
;; are layers on top and are reported, not limited.

(def kernel-files ["core.clj" "ext.clj"])

(defn- lines-in [names] (reduce + (map #(count (str/split-lines (slurp (str (fs/path root "src" "bba" %))))) names)))

(deftest ac7-core-size
  (let [all (map (comp str fs/file-name) (fs/glob (fs/path root "src" "bba") "*.clj"))
        kernel (lines-in kernel-files)]
    (println (str "  [ac7] kernel " kernel " lines; all of src/bba " (lines-in all) " lines"))
    (is (< kernel 500) (str "kernel is " kernel " lines"))))

(deftest ac8-tool-errors-are-results
  (let [dirs (temp-dirs)
        fp (fake-provider [[(tool-use "u1" "nope" {})
                            (tool-use "m1" "execute_form" {})
                            (tool-use "x1" "execute_form" {:code "(/ 1 0)"})
                            (tool-use "d1" "execute_form" {:code "(def sneaky 1)"})]
                           [(text "done")]])
        {:keys [code]} (run-bba dirs fp ["-p" "x"])
        rs (tool-results (second @(:calls fp)))]
    (is (= 0 code))
    (is (= 4 (count rs)))
    (is (every? :is_error rs))
    (is (str/includes? (:content (nth rs 0)) "unknown tool"))
    (is (str/includes? (:content (nth rs 1)) "missing input: code"))
    (is (str/includes? (:content (nth rs 2)) "Divide by zero"))
    (is (str/includes? (:content (nth rs 3)) "must not define vars"))))

(deftest ac9-entry-point-name
  (is (fs/exists? (fs/path root "bin" "bba")))
  (is (not (fs/exists? (fs/path root "bin" "bb")))))

(deftest max-turns-bound
  (let [dirs (temp-dirs)
        loop-reply [(tool-use "l" "execute_form" {:code "(+ 1 1)"})]
        fp (fake-provider (repeat 10 loop-reply))
        {:keys [code out]} (run-bba dirs fp ["-p" "x" "--max-turns" "3"])]
    (is (= 1 code))
    (is (= 3 (count @(:calls fp))))
    (is (str/includes? out "max turns"))))

(deftest agents-md-in-system-prompt
  (let [{:keys [cwd home]} (temp-dirs)]
    (fs/create-dirs cwd) (fs/create-dirs home)
    (spit (str (fs/path cwd "AGENTS.md")) "PROJECT-RULE")
    (spit (str (fs/path home "AGENTS.md")) "USER-RULE")
    (let [s (core/system-prompt {:cwd cwd :home home})]
      (is (str/includes? s "PROJECT-RULE"))
      (is (str/includes? s "USER-RULE")))))

(deftest session-file-written-without-key
  (let [dirs (temp-dirs)
        fp (fake-provider [[(text "hello")]])
        {:keys [code]} (run-bba dirs fp ["-p" "hi"] :env {"ANTHROPIC_API_KEY" "sk-SECRET-123"})
        [f] (fs/glob (fs/path (:cwd dirs) ".bba" "sessions") "*.jsonl")
        lines (str/split-lines (slurp (str f)))]
    (is (= 0 code))
    (is (= 2 (count lines)))
    (is (str/includes? (first lines) "\"role\":\"user\""))
    (is (not (str/includes? (slurp (str f)) "sk-SECRET-123")))
    (is (= "*\n" (slurp (str (fs/path (:cwd dirs) ".bba" "sessions" ".gitignore"))))
        "sessions folder is git-ignored")))

(deftest reload-drops-broken-file-registrations
  (let [{:keys [cwd home]} (temp-dirs)
        ctx {:cwd cwd :home home}
        f (str (fs/path cwd ".bba" "extensions" "r.clj"))]
    (binding [ext/*source* :builtin] (ext/register-tool! {:name "execute_form" :handler (fn [_ _] "ok")}))
    (fs/create-dirs (fs/parent f))
    (spit f reverse-src)
    (core/load-extensions! ctx)
    (is (ext/tool "reverse"))
    (spit f "(ns broken")
    (binding [*err* (java.io.StringWriter.)] (core/load-extensions! ctx))
    (is (nil? (ext/tool "reverse")))
    (is (ext/tool "execute_form") "built-ins survive reload")))

(deftest hook-error-fails-closed-and-override-warns
  (let [ran (atom false)]
    (binding [ext/*source* :builtin] (ext/register-tool! {:name "execute_form" :handler (fn [_ _] (reset! ran true) "ran")}))
    (let [err (java.io.StringWriter.)]
      (binding [*err* err ext/*source* "/x/ext.clj"]
        (ext/on! :tool-call (fn [_ _] (throw (Exception. "boom"))))
        (ext/register-tool! {:name "develop_form" :handler (fn [_ _] "mine")})
        (ext/register-tool! {:name "execute_form" :handler (fn [_ _] "mine")}))
      (is (str/includes? (str err) "replaces :builtin"))
      (let [r (binding [*err* (java.io.StringWriter.)]
                (core/run-tool {:id "1" :name "execute_form" :input {:code "(+ 1 1)"}} {:cwd "."}))]
        (is (:is_error r))
        (is (str/includes? (:content r) "hook error in /x/ext.clj: boom"))
        (is (not @ran))))))

(deftest anthropic-request-shape
  (let [{:keys [uri headers body]}
        (provider/build-request {:messages [{:role "user" :content "hi"}] :system "sys"
                                 :tools [{:name "bash" :description "d" :input_schema {:type "object"}}]}
                                {:api-key "k"})]
    (is (= "https://api.anthropic.com/v1/messages" uri))
    (is (= {"x-api-key" "k" "anthropic-version" "2023-06-01" "content-type" "application/json"} headers))
    (is (= "claude-opus-5-5" (:model body)))
    (is (= 8192 (:max_tokens body)))
    (is (= "sys" (:system body)))
    (is (= "input_schema" (name (first (keys (dissoc (first (:tools body)) :name :description))))))))

(deftest parse-args-cases
  (is (= {:prompt "x" :max-turns 2} (main/parse-args ["-p" "x" "--max-turns" "2"])))
  (is (:error (main/parse-args ["-p"])))
  (is (:error (main/parse-args ["--bogus"]))))

;; ---------------------------------------------------------------- gate 5 additions

(defn- with-local-api
  "Run f against a local HTTP server that answers every request with `status` and `body`.
  Points bba.provider/api-url at it. Returns [result requests]."
  [status body f]
  (let [reqs (atom [])
        stop (org.httpkit.server/run-server
              (fn [req] (swap! reqs conj {:headers (:headers req) :body (slurp (:body req))})
                {:status status :headers {"content-type" "application/json"} :body body})
              {:port 0 :legacy-return-value? false})
        port (org.httpkit.server/server-port stop)]
    (try [(with-redefs [provider/api-url (str "http://127.0.0.1:" port "/v1/messages")] (f)) @reqs]
         (finally (org.httpkit.server/server-stop! stop)))))

(deftest r1-anthropic-provider-over-http
  (testing "2xx: posts JSON with headers and returns the parsed message"
    (let [[r reqs] (with-local-api 200 "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"pong\"}]}"
                     #((provider/anthropic {"ANTHROPIC_API_KEY" "k1"})
                       {:messages [{:role "user" :content "ping"}] :system "s" :tools []}))
          [{:keys [headers body]}] reqs]
      (is (= "pong" (-> r :content first :text)))
      (is (= "k1" (get headers "x-api-key")))
      (is (= "2023-06-01" (get headers "anthropic-version")))
      (is (str/includes? body "\"model\":\"claude-opus-5-5\""))))
  (testing "non-2xx: readable 'API error <status>: <msg>'"
    (let [[e] (with-local-api 401 "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"
                #(try ((provider/anthropic {"ANTHROPIC_API_KEY" "bad"}) {:messages [] :system "s"})
                      nil (catch Exception e e)))]
      (is (= "API error 401: invalid x-api-key" (ex-message e)))))
  (testing "2xx with non-JSON body"
    (let [[e] (with-local-api 200 "<html>"
                #(try ((provider/anthropic {"ANTHROPIC_API_KEY" "k"}) {:messages [] :system "s"})
                      nil (catch Exception e e)))]
      (is (str/includes? (ex-message e) "not JSON")))))

(deftest provider-error-in-print-mode-exits-1
  (let [dirs (temp-dirs)
        fp {:fn (fn [_] (throw (ex-info "API error 529: overloaded" {})))}
        {:keys [code err]} (run-bba dirs fp ["-p" "hi"])]
    (is (= 1 code))
    (is (str/includes? err "API error 529: overloaded"))
    (is (not (str/includes? err "at clojure.")) "no stack trace")))

(deftest interactive-survives-provider-and-command-errors
  (let [dirs (temp-dirs)
        n (atom 0)
        fp {:fn (fn [_] (if (= 1 (swap! n inc))
                          (throw (ex-info "API error 500: boom" {}))
                          {:role "assistant" :content [(text "recovered")] :stop_reason "end_turn"}))}
        bad-cmd "(ns bba.extensions.bad (:require [bba.ext :as ext]))
                 (ext/register-command! \"bad\" (fn [_ _] (throw (Exception. \"cmd-fail\"))))"
        _ (fs/create-dirs (fs/path (:cwd dirs) ".bba" "extensions"))
        _ (spit (str (fs/path (:cwd dirs) ".bba" "extensions" "bad.clj")) bad-cmd)
        {:keys [code out err]} (run-bba dirs fp [] :input "first\n/bad\n/nope\nsecond\n")]
    (is (= 0 code) "EOF exits 0")
    (is (str/includes? err "API error 500: boom"))
    (is (str/includes? err "cmd-fail"))
    (is (str/includes? out "unknown command: /nope"))
    (is (str/includes? out "recovered"))))

(deftest session-start-event-and-no-extensions-flag
  (let [dirs (temp-dirs)
        _ (fs/create-dirs (fs/path (:cwd dirs) ".bba" "extensions"))
        _ (spit (str (fs/path (:cwd dirs) ".bba" "extensions" "s.clj"))
                "(ns bba.extensions.s (:require [bba.ext :as ext]))
                 (ext/on! :session-start (fn [_ _] (println \"SESSION-START\")))")
        fp (fake-provider [[(text "a")] [(text "b")]])]
    (is (str/includes? (:out (run-bba dirs fp ["-p" "x"])) "SESSION-START"))
    (is (not (str/includes? (:out (run-bba dirs fp ["-p" "x" "--no-extensions"])) "SESSION-START")))))

(deftest nfr4-startup-time
  ;; Measures, does not tune: generous bound (one sample is noisy).
  (let [t0 (System/currentTimeMillis)
        r @(p/process {:out :string :err :string} bb-bin (str (fs/path root "bin" "bba")) "--help")
        ms (- (System/currentTimeMillis) t0)]
    (println (str "  [nfr4] bin/bba --help took " ms " ms"))
    (is (= 0 (:exit r)))
    (is (str/includes? (:out r) "usage: bba"))
    (is (< ms 5000))))

(deftest sys-sh-helper
  (let [dir (:base (temp-dirs))]
    (testing "exit code and stderr"
      (let [r (sys/sh "echo out; echo err >&2; exit 3" {:dir dir})]
        (is (str/includes? r "out"))
        (is (str/includes? r "err"))
        (is (str/includes? r "[exit 3]"))))
    (testing "runs in :dir"
      (is (= (str (fs/canonicalize dir)) (str (fs/canonicalize (str/trim (sys/sh "pwd" {:dir dir})))))))
    (testing "timeout kills the command and keeps partial output"
      (let [t0 (System/currentTimeMillis)
            r (sys/sh "echo early; sleep 30" {:dir dir :timeout-s 1})]
        (is (< (- (System/currentTimeMillis) t0) 10000))
        (is (str/includes? r "early"))
        (is (str/includes? r "timed out"))))))
