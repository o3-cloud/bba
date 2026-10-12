(ns ucii-check-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ucii-check :as uc]))

(def manifest (json/parse-string (slurp "ucii.json")))
(def makefile (slurp "Makefile"))

(defn- codes [found] (set (map (juxt :operation :code) found)))

(deftest the-real-interface-checks-out
  (is (= [] (uc/problems manifest makefile (partial uc/make-resolves? "."))))
  (is (= (set (keys (get manifest "operations"))) (set (uc/tagged-targets makefile)))))

(deftest make-dry-run-resolves-only-real-targets
  (is (uc/make-resolves? "." "verify"))
  (is (not (uc/make-resolves? "." "no-such-target"))))

(def always (constantly true))

(deftest a-broken-manifest-is-refused
  (testing "an advertised operation with no target"
    (let [m (assoc-in manifest ["operations" "build"]
                      {"description" "x" "standard" true "invoke" "make build"
                       "effects" "local" "idempotent" true})
          found (uc/problems m makefile #(not= "build" %))]
      (is (contains? (codes found) ["build" "not-resolved"]))
      (is (contains? (codes found) ["build" "not-tagged"]))))
  (testing "a tagged target the manifest leaves out"
    (let [m (update manifest "operations" dissoc "fmt")]
      (is (contains? (codes (uc/problems m makefile always)) ["fmt" "not-advertised"]))))
  (testing "a read-only name advertised with side effects"
    (let [m (assoc-in manifest ["operations" "verify" "effects"] "local")]
      (is (contains? (codes (uc/problems m makefile always)) ["verify" "effects-contradict-name"]))))
  (testing "a parameter the Makefile never reads"
    (let [m (assoc-in manifest ["operations" "agent" "parameters" "prompt" "maps"] "NOPE=<value>")]
      (is (contains? (codes (uc/problems m makefile always)) ["agent" "parameter-unused"]))))
  (testing "an operation left out of .PHONY"
    (let [mf (str/replace makefile #"(?m)^\.PHONY: describe test " ".PHONY: describe ")]
      (is (not= mf makefile) "the edit must change the Makefile, or this test proves nothing")
      (is (contains? (codes (uc/problems manifest mf always)) ["test" "not-phony"]))))
  (testing "an operation not marked optional"
    (let [m (update-in manifest ["operations" "fmt"] dissoc "required")]
      (is (contains? (codes (uc/problems m makefile always)) ["fmt" "required-wrong"]))))
  (testing "a wrong standard flag and a wrong spec"
    (let [m (-> manifest
                (assoc-in ["operations" "agent" "standard"] true)
                (assoc "spec" "ucii/v9"))]
      (is (contains? (codes (uc/problems m makefile always)) ["agent" "standard-wrong"]))
      (is (contains? (codes (uc/problems m makefile always)) [nil "spec-version"])))))

(deftest phony-targets-joins-continuations
  (is (= #{"a" "b" "c"} (uc/phony-targets ".PHONY: a \\\n\tb\n.PHONY: c\n"))))

(deftest every-problem-is-reported
  (let [m (-> manifest
              (assoc-in ["operations" "verify" "effects"] "local")
              (assoc-in ["operations" "test" "invoke"] "bb test")
              (update "operations" dissoc "describe"))
        found (uc/problems m makefile always)]
    (is (<= 4 (count found)) (str/join "\n" (map pr-str found)))
    (is (every? #(and (:code %) (:message %)) found))))
