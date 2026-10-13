(ns review-regression-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [bba.world :as world]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.openai :as openai]
            [bba.responses :as responses]
            [bba.ui :as ui]
            [bba.toolview :as toolview]))

(deftest checkpoint-validation-is-bounded
  ;; A finite sentinel makes the old unlimited traversal fail without a runaway print.
  (let [seen (atom 0)
        xs (map (fn [i] (swap! seen inc)
                  (when (> i 2000) (throw (ex-info "unbounded traversal" {})))
                  i) (range 3000))]
    (is (false? ((deref (ns-resolve 'bba.world 'printable?)) xs)))
    (is (< @seen 2000) "Validation must stop before it traverses the entire sequence")))

(deftest private-vars-obey-world-contract
  (let [d (str (fs/create-temp-dir {:prefix "bba-review-test-"}))]
    (ext/reset-all!)
    (try
      (world/start! {:cwd d})
      (is (= :rejected (:status (world/attempt! {:op :execute :code "(def ^:private hidden (atom 0))"}))))
      (is (= ['helper] (:defined (world/attempt! {:op :develop :code "(defn- helper [x] (inc x))"}))))
      (is (= :accepted (:status (world/attempt! {:op :develop :remove ["helper"]}))))
      (world/attempt! {:op :develop :code "(def ^:private counter (atom 0))"})
      (world/attempt! {:op :execute :code "(swap! counter inc)"})
      (world/attempt! {:op :execute :code "(/ 1 0)"})
      (is (= 1 @@(ns-resolve 'world 'counter)))
      (finally (world/stop!) (reset! world/state nil) (ext/reset-all!) (fs/delete-tree d)))))

(deftest damaged-session-recovers-only-valid-prefix
  (let [f (str (fs/create-temp-file {:prefix "bba-review-session-"}))
        exchange [(core/user-message "hello") {:role "assistant" :content [{:type "text" :text "ok"}]}]
        prefix (apply str (map #(str (json/generate-string %) "\n") exchange))]
    (try
      (spit f (str prefix "{"))
      (is (= exchange (core/load-session f)))
      (spit f (str prefix "{\n" prefix))
      (is (= exchange (core/load-session f)) "Do not replay records after corruption")
      (finally (fs/delete f)))))

(deftest empty-chat-replies-fail
  (doseq [body [{} {:choices []} {:choices [{:message {:content ""}}]}
                (openai/stream-body openai/stream-init)]]
    (is (thrown? clojure.lang.ExceptionInfo (openai/->reply body)))))

(deftest incomplete-response-is-not-success
  (let [reply (responses/->reply (assoc responses/stream-init :text "partial" :incomplete "max_output_tokens"))]
    (is (= "max_tokens" (:stop_reason reply)))
    (is (= "max_output_tokens" (:incomplete reply)))
    (let [f (str (fs/create-temp-file {:prefix "bba-review-turn-"}))]
      (ext/reset-all!)
      (try
        (ext/set-provider! (constantly reply))
        (let [r (core/run-turns {:env {} :session-file f} [(core/user-message "hello")])]
          (is (= :incomplete (:status r)))
          (is (= "max_output_tokens" (:incomplete r)))
          (is (re-find #"(?i)incomplete" (:text r))))
        (finally (ext/reset-all!) (fs/delete f))))))

(deftest spinner-stop-under-lock-is-safe
  (let [lock @(ns-resolve 'bba.ui 'lock)
        old-on @ui/on old-worker @ui/worker]
    (when old-worker (future-cancel old-worker))
    (reset! ui/worker nil)
    (reset! ui/on true)
    (try
      (with-redefs [ui/clear-line (fn [])]
        (with-out-str
          (locking lock
            (ui/start! "test")
            ;; Hold the lock until the worker reaches its first tick.
            (Thread/sleep 300)
            (ui/stop!)))
        (is (= :stopped (try (deref @ui/worker 2000 :timeout) :stopped
                             (catch Exception _ :failed)))))
      (finally (when @ui/worker (future-cancel @ui/worker))
               (reset! ui/state nil) (reset! ui/worker nil) (reset! ui/on old-on)))))

(deftest tool-call-width-includes-removals
  (with-redefs [ui/enabled? (constantly false)]
    (let [old @ui/on]
      (reset! ui/on false)
      (try
        (doseq [width [0 1 8 20 80] tool ["develop_form" "execute_form" "other-tool"]]
          (is (<= (count (toolview/call-str tool {:code (apply str (repeat 100 "x")) :remove ["foo" "bar"]} width)) width)))
        (finally (reset! ui/on old))))))
