(ns jev-guardrail-test
  "The Jev guardrail: the block decision, the event the display shows, and the
  transcript block. The guardrail ships as a core extension file, not as a
  classpath namespace, so the tests load that file directly."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [bba.ext :as ext]
            [bba.provider :as provider]
            [bba.toolview :as toolview]))

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def extension-file (str root "/extensions/jev-guardrail.clj"))

;; Load the extension the way bba.core/load-extension! does, so the :tool-call hook is
;; tagged with the file and a test reset can drop it.
(binding [ext/*source* extension-file] (load-file extension-file))
(alias 'j 'bba.extensions.jev-guardrail)

(def ^:private enabled {"BBA_GUARDRAIL_ENABLED" "true"})

(defn- replies
  "Run `f` with the Jev provider returning `text` for every call."
  [text f]
  (with-redefs [provider/systemone (fn [_] (fn [_] (let [d (cheshire.core/parse-string text true)] {:answers {:score {:type "noul" :noul (/ (:score d) 100.0)}} :reason (:reason d)})))]
    (f)))

(defn- call [ctx] (j/guard {:name "shell" :input {:cmd "rm -rf /some/dir"}} ctx))

(deftest enabled-by-default
  (testing "a missing value enables the guardrail"
    (replies "{\"score\":99,\"dangerous\":true,\"reason\":\"danger\"}"
             #(is (:block (call {:env {}})) "the guardrail is on by default")))
  (testing "the exact value false disables the guardrail"
    (with-redefs [provider/systemone (fn [_] (throw (ex-info "Jev must not be called" {})))]
      (is (nil? (call {:env {"BBA_GUARDRAIL_ENABLED" "false"}})))
      (is (nil? (call {:env {"BBA_GUARDRAIL_ENABLED" "FALSE"}}))
          "the value is case-insensitive")))
  (testing "any other value enables the guardrail"
    (replies "{\"score\":99,\"dangerous\":true,\"reason\":\"danger\"}"
             #(is (:block (call {:env {"BBA_GUARDRAIL_ENABLED" "yes"}}))))))

(deftest enabled?-reads-the-environment
  (is (true? (j/enabled? {})) "the guardrail is enabled by default")
  (is (true? (j/enabled? {"BBA_GUARDRAIL_ENABLED" "true"})))
  (is (false? (j/enabled? {"BBA_GUARDRAIL_ENABLED" "false"})))
  (is (false? (j/enabled? {"BBA_GUARDRAIL_ENABLED" "FALSE"}))))

(deftest a-score-at-the-threshold-blocks
  (replies "{\"score\":68,\"dangerous\":false,\"reason\":\"safe\"}"
           #(is (nil? (call {:env enabled})) "score 68 does not block"))
  (replies "{\"score\":69,\"dangerous\":true,\"reason\":\"danger\"}"
           #(is (:block (call {:env enabled})) "score 69 blocks"))
  (replies "{\"score\":69,\"reason\":\"danger\"}"
           #(is (= 69 (:score (j/classify {:env enabled} "shell" {}))) "0.69 is read as 69"))
  (replies "{\"score\":50}"
           #(is (:block (call {:env (assoc enabled "BBA_GUARDRAIL_THRESHOLD" "50")}))
                "the threshold comes from the environment"))
  (replies "{\"score\":69}"
           #(is (str/includes? (:reason (call {:env enabled})) "dangerous action")
                "a missing reason falls back to a fixed text")))

(deftest a-classification-failure-fails-closed
  (testing "invalid JSON blocks"
    (replies "not json" #(is (:block (call {:env enabled})))))
  (testing "a provider error blocks"
    (with-redefs [provider/systemone (fn [_] (fn [_] (throw (ex-info "offline" {}))))]
      (is (:block (call {:env enabled})))))
  (testing "a score outside 0..100 blocks"
    (doseq [_ ["{\"score\":101}" "{\"score\":-1}" "{\"dangerous\":true}"]]
      (is (:block (call {:env enabled}))))))

(deftest parse-decision-validates
  (is (= 5 (:score (j/parse-decision "{\"score\":5}"))))
  (is (thrown? Exception (j/parse-decision "{\"score\":101}")))
  (is (thrown? Exception (j/parse-decision "{\"reason\":\"missing\"}")))
  (is (thrown? Exception (j/parse-decision "not json"))))

(deftest threshold-reads-the-environment
  (is (= 69 (j/threshold {})))
  (is (= 50 (j/threshold {"BBA_GUARDRAIL_THRESHOLD" "50"})))
  (is (= 69 (j/threshold {"BBA_GUARDRAIL_THRESHOLD" "nonsense"})))
  (is (= 69 (j/threshold {"BBA_GUARDRAIL_THRESHOLD" "101"}))))

(deftest the-display-event-carries-the-verdict-and-the-prompt
  (let [events (atom [])]
    (replies "{\"score\":99,\"dangerous\":true,\"reason\":\"recursive deletion\"}"
             #(call {:env enabled :show (fn [e] (swap! events conj e))}))
    (let [e (first @events)]
      (is (= 1 (count @events)) "one event for one call")
      (is (= :guardrail (:phase e)))
      (is (= "shell" (:tool e)))
      (is (= 99 (:score e)))
      (is (true? (:dangerous e)))
      (is (true? (:blocked e)))
      (is (= "recursive deletion" (:reason e)))
      (is (= {:cmd "rm -rf /some/dir"} (:input e)))
      (is (str/includes? (:source e) "Tool name: shell")
          "the source is the exact prompt sent to Jev")
      (is (str/includes? (:source e) "rm -rf /some/dir"))
      (is (str/includes? (:text e) "score=99")
          "a session with no terminal gets a one-line form")
      (is (not (str/includes? (:text e) "\n")) "that form is one line"))))

(deftest an-allowed-call-is-reported-too
  (let [events (atom [])]
    (replies "{\"score\":10,\"dangerous\":false,\"reason\":\"safe\"}"
             #(call {:env enabled :show (fn [e] (swap! events conj e))}))
    (let [e (first @events)]
      (is (false? (:blocked e)))
      (is (= 10 (:score e))))))

(deftest a-failure-event-shows-score-100-and-no-secret
  (let [events (atom [])
        secret "sk-SECRET-123"]
    (with-redefs [provider/systemone
                  (fn [_] (fn [_] (throw (ex-info (str "401 unauthorized " secret)
                                                  {:status 401}))))]
      (let [r (call {:env enabled :show (fn [e] (swap! events conj e))})
            e (first @events)]
        (is (:block r))
        (is (= 100 (:score e)) "a failure is reported as the worst score")
        (is (true? (:dangerous e)))
        (is (true? (:blocked e)))
        (is (str/includes? (:reason e) "classification failed"))
        (is (str/includes? (:reason e) "401") "the HTTP status is kept")
        (is (not (str/includes? (:reason e) secret))
            "the provider message can echo the request, so it is not shown (AC-13)")
        (is (not (str/includes? (:text e) secret)))
        (is (not (str/includes? (:reason r) secret)))))))

(deftest a-failure-without-a-status-names-the-error-class
  (let [events (atom [])]
    (with-redefs [provider/systemone (fn [_] (fn [_] (throw (IllegalStateException. "gone"))))]
      (call {:env enabled :show (fn [e] (swap! events conj e))})
      (is (str/includes? (:reason (first @events)) "IllegalStateException")))))

(deftest the-transcript-block-shows-the-verdict-reason-and-source
  (let [e {:tool "shell" :score 99 :dangerous true :blocked true
           :reason "recursive deletion of a directory"
           :source "Classify this BBA tool call for danger.\nTool name: shell"}
        s (toolview/guardrail-str e 80)]
    (is (= (str "Jev guardrail shell score=99 dangerous=true blocked=true"
                "\nrecursive deletion of a directory"
                "\nSource sent to Jev:"
                "\nClassify this BBA tool call for danger."
                "\nTool name: shell")
           s))))

(deftest an-allowed-call-is-one-line
  (let [s (toolview/guardrail-str {:tool "shell" :score 10 :dangerous false :blocked false
                                   :reason "reading a file"
                                   :source "Classify this BBA tool call for danger."} 100)]
    (is (= "Jev guardrail shell score=10 dangerous=false blocked=false: reading a file" s)
        "an allowed call is one line, with the reason")
    (is (= 1 (count (str/split-lines s))))))

(deftest the-transcript-block-is-bounded
  (testing "a long source line is clipped to the screen width"
    (let [s (toolview/guardrail-str {:tool "shell" :score 99 :blocked true
                                     :source (apply str (repeat 200 "x"))} 20)]
      (is (every? #(<= (count %) 20) (str/split-lines s)))))
  (testing "a very tall source cannot flood the transcript"
    (let [s (toolview/guardrail-str {:tool "shell" :score 99 :blocked true
                                     :source (str/join "\n" (repeat 50 "line"))} 80)]
      (is (str/includes? s "… 38 more lines"))
      (is (= 16 (count (str/split-lines s)))
          "the header, the reason, the label, 12 source lines and the overflow line")))
  (testing "a missing reason and source still render"
    (let [s (toolview/guardrail-str {:tool "shell" :score 100 :blocked true} 40)]
      (is (str/includes? s "no reason")))))
