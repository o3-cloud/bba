(ns context-test
  "The context extension: the window table, the per-category breakdown and the
  turn rule that replaces the default one. The extension ships as a core file,
  not as a classpath namespace, so the test loads it directly."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [bba.core :as core]
            [bba.ext :as ext]))

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def extension-file (str root "/extensions/context.clj"))

(defn- load-extension!
  "Load the extension as core does. Another test in the suite can run /reload,
  which drops every non-builtin registration, so each test loads the file."
  []
  (binding [ext/*source* extension-file] (load-file extension-file)))

(use-fixtures :each (fn [f] (load-extension!) (f)))

(load-extension!)
(alias 'c 'bba.extensions.context)

(def ^:private ollama {"BBA_PROVIDER" "ollama" "BBA_MODEL" "deepseek-v4.1-flash:cloud"})
;; ------------------------------------------------------------------ window

(deftest the-table-gives-the-window
  (is (= 1048576 (c/table-window "deepseek-v4.1-flash:cloud")))
  (is (= 272000 (c/table-window "gpt-5.6-sol")))
  (is (nil? (c/table-window "a-model-nobody-listed"))))

(deftest the-environment-overrides-the-table
  (is (= 500000 (c/env-window {"BBA_CONTEXT_WINDOW" "500000"})))
  (is (= 500000 (c/window (assoc ollama "BBA_CONTEXT_WINDOW" "500000")))
      "the override wins over the table")
  (testing "an unreadable override is ignored, not reported"
    (is (nil? (c/env-window {"BBA_CONTEXT_WINDOW" "lots"})))
    (is (nil? (c/env-window {"BBA_CONTEXT_WINDOW" "-5"})))
    (is (nil? (c/env-window {"BBA_CONTEXT_WINDOW" "  "})))
    (is (= 1048576 (c/window (assoc ollama "BBA_CONTEXT_WINDOW" "lots"))))))

(deftest an-unknown-model-has-no-window
  (is (nil? (c/window {"BBA_PROVIDER" "anthropic" "BBA_MODEL" "an-unlisted-model"}))))

(deftest the-source-of-the-window-is-named
  (is (= :env (c/window-source (assoc ollama "BBA_CONTEXT_WINDOW" "1000") nil)))
  (is (= :table (c/window-source ollama nil)))
  (is (= :probe (c/window-source {"BBA_PROVIDER" "ollama" "BBA_MODEL" "unlisted"} 32000)))
  (is (= :unknown (c/window-source {"BBA_PROVIDER" "ollama" "BBA_MODEL" "unlisted"} nil)))
  (is (= :table (c/window-source ollama nil)) "the table wins over a probe")
  (is (= "static table" (c/source-note {:source :table :window 1}))))

;; ------------------------------------------------------------------- probe

(deftest the-window-comes-from-the-key-that-carries-the-family
  (is (= 1048576 (c/context-length {"deepseek_v41.context_length" 1048576
                                    "deepseek_v41.embedding_length" 4096})))
  (is (= 8192 (c/context-length {"llama.context_length" 8192})))
  (is (nil? (c/context-length {"general.architecture" "llama"})))
  (is (nil? (c/context-length nil))))

(deftest the-probe-addresses-the-local-server
  (is (= "http://localhost:11434" (c/ollama-url {})))
  (is (= "http://10.0.0.2:11434" (c/ollama-url {"OLLAMA_HOST" "10.0.0.2:11434"})))
  (is (= "http://host:11434" (c/ollama-url {"OLLAMA_HOST" "http://host:11434/"}))))

(deftest the-probe-is-asked-once-and-keeps-a-nil-answer
  (let [asked (atom 0)]
    (with-redefs [c/probe! (fn [_] (swap! asked inc) 4096)]
      (reset! c/state {})
      (is (= 4096 (c/remember-probe! {:env {"BBA_PROVIDER" "ollama"}})))
      (is (= 4096 (c/remember-probe! {:env {"BBA_PROVIDER" "ollama"}})))
      (is (= 1 @asked) "the second call reads the first answer"))
    (with-redefs [c/probe! (fn [_] (swap! asked inc) nil)]
      (reset! c/state {})
      (is (nil? (c/remember-probe! {:env {}})))
      (is (nil? (c/remember-probe! {:env {}})))
      (is (= 2 @asked) "a server that answers nil is not asked again"))))
;; -------------------------------------------------------------- breakdown

(def ^:private spec-a {:name "execute_form" :description "Run code." :input_schema {:type "object"}})

(def ^:private spec-skill {:name "activate_skill"
                           :description (str/join (repeat 4000 "s"))
                           :input_schema {:type "object"}})

(deftest the-tools-are-counted-and-the-catalog-is-named-apart
  (let [p (c/tools-part [spec-a spec-skill])
        total (+ (c/json-chars spec-a) (c/json-chars spec-skill))]
    (is (= 2 (:count p)))
    (is (= total (:chars p)))
    (is (= (c/estimate total) (:tokens p)))
    (is (= (c/estimate (c/json-chars spec-skill)) (:skills p))
        "activate_skill is reported as the skills catalog")
    (is (= (- (:tokens p) (:skills p)) (:other-skill p))
        "the other tools are what is left after the catalog")))

(deftest the-skills-catalog-does-not-inflate-the-total
  (let [p (c/tools-part [spec-a spec-skill])]
    (is (= (:tokens p) (+ (:skills p) (:other-skill p)))
        "the named parts add up to the whole, so the report never counts twice")))

(deftest the-messages-are-split-by-role
  (let [msgs [{:role "user" :content [{:type "text" :text (str/join (repeat 400 "u"))}]}
              {:role "assistant" :content [{:type "text" :text (str/join (repeat 80 "a"))}]}
              {:role "user" :content [{:type "tool_result"
                                       :tool_use_id "1"
                                       :content (str/join (repeat 120 "t"))}]}]
        p (c/messages-part msgs)]
    (is (= 3 (:count p)))
    (is (= 100 (:user p)))
    (is (= 20 (:assistant p)))
    (is (pos? (:tool p)))
    (is (= (+ (:user p) (:assistant p) (:tool p)) (:tokens p)))))

(deftest the-estimate-matches-compaction
  (is (= 25 (c/estimate 100)))
  (is (zero? (c/estimate 3))))

(deftest the-share-is-a-whole-percent
  (is (= 50 (c/percent 100 200)))
  (is (zero? (c/percent 0 200)))
  (is (zero? (c/percent 100 nil)) "an unknown window has no share")
  (is (zero? (c/percent 100 0))))
;; ---------------------------------------------------------------- measure

(def ^:private env {:env {"BBA_PROVIDER" "ollama" "BBA_MODEL" "deepseek-v4.1-flash:cloud"}})

(defn- request []
  {:system "You are bba."
   :tools [spec-a spec-skill]
   :messages [{:role "user" :content [{:type "text" :text "hello"}]}]})

(deftest the-provider-count-wins-over-the-estimate
  (let [m (c/measure {:request (request) :last-usage {:input 10486}} (:env env) nil)]
    (is (= 10486 (:used m)) "the provider counted the prompt itself")
    (is (= 1 (:percent m)) "10486 of 1048576 is one percent")
    (is (= (- 1048576 10486) (:free m)))))

(deftest without-a-provider-count-the-parts-are-summed
  (let [m (c/measure {:request (request)} (:env env) nil)
        parts (+ (:system m) (get-in m [:tools :tokens]) (get-in m [:messages :tokens]))]
    (is (= parts (:used m)))))

(deftest the-sent-view-and-the-file-are-reported-apart
  (let [full [{:role "user" :content [{:type "text" :text (str/join (repeat 4000 "x"))}]}
              {:role "assistant" :content [{:type "text" :text "ok"}]}]
        view (vec (take-last 1 full))
        m (c/measure {:request (assoc (request) :messages view) :messages full}
                     (:env env) nil)]
    (is (= 1 (:sent-count m)))
    (is (:on-disk-differs? m) "compaction sent less than the file holds")
    (is (> (get-in m [:on-disk :chars]) (get-in m [:messages :chars])))
    (is (str/includes? (c/report m) "on disk")))
  (let [req (request)
        m (c/measure {:request req :messages (:messages req)} (:env env) nil)]
    (is (not (:on-disk-differs? m)) "the file is what was sent")))

(deftest an-unknown-window-still-breaks-down-the-prompt
  (let [m (c/measure {:request (request) :last-usage {:input 900}}
                     {"BBA_PROVIDER" "anthropic" "BBA_MODEL" "unlisted"}
                     nil)]
    (is (nil? (:window m)))
    (is (= 900 (:used m)))
    (is (nil? (:free m)))
    (is (str/includes? (c/used-str m) "window unknown"))))
;; ------------------------------------------------------------ the surfaces

(deftest the-command-is-registered
  (is (some? (ext/command "context")) "/context is a real command")
  (is (= extension-file (:source (ext/command "context")))))

(deftest the-turn-stop-hook-replaces-the-rule
  (reset! c/state {})
  (let [facts {:tools 3 :millis 8000 :usage {:input 12000 :output 900}
               :last-usage {:input 11000} :cost 0.04 :width 100
               :request (request) :messages (:messages (request))}
        out (c/turn-stop facts env)]
    (is (string? (:turn-rule out)) "the hook answers with a rule")
    (is (str/includes? (:turn-rule out) "3 tools"))
    (is (str/includes? (:turn-rule out) "12k in"))
    (is (str/includes? (:turn-rule out) "$0.04"))
    (is (str/includes? (:turn-rule out) "8s"))
    (is (str/includes? (:turn-rule out) "ctx 1%"))
    (is (str/includes? (:turn-rule out) "left"))
    (is (= 11000 (:used (:last @c/state)))
        "the measurement is kept for /context")))

(deftest the-rule-stays-on-one-line
  (reset! c/state {})
  (let [facts {:tools 3 :millis 8000 :usage {:input 12000 :output 900} :cost 0.04
               :request (request) :width 30}
        rule (:turn-rule (c/turn-stop facts env))]
    (is (<= (count rule) 30))
    (is (not (str/includes? rule "\n")))))

(deftest a-broken-measurement-keeps-the-default-rule
  (with-redefs [c/measure (fn [& _] (throw (ex-info "boom" {})))]
    (is (nil? (c/turn-stop {:request (request)} env))
        "a nil answer tells main to print the default rule")))

(deftest a-turn-with-no-numbers-has-no-rule
  (reset! c/state {})
  (is (nil? (c/turn-stop {} {:env {}})) "no field, no rule"))

(deftest the-hook-is-registered-for-turn-stop
  (testing "the last registration for the event is this file's"
    (is (= extension-file (:source (last (ext/hooks :turn-stop)))))))

(deftest the-report-shows-every-category
  (reset! c/state {})
  (let [facts {:request (request) :usage {:input 500 :output 40} :millis 1000}
        _ (c/turn-stop facts env)
        text (c/report (:last @c/state))]
    (doseq [label ["context" "provider" "prompt" "tools" "messages" "turn"]]
      (is (str/includes? text label) (str label " has a row")))
    (is (str/includes? text "deepseek-v4.1-flash:cloud"))))
;; ------------------------------------------------------------ end to end

(defn- fake-provider
  "A provider that replies with plain text and the usage it is given."
  [usage]
  (fn [_req]
    {:role "assistant"
     :content [{:type "text" :text "ok"}]
     :stop_reason "end_turn"
     :usage usage}))

(deftest the-turn-reports-the-last-prompt
  (let [dir (str (fs/create-temp-dir {:prefix "bba-context"}))]
    (try
      (with-redefs [ext/provider (constantly (fake-provider {:input 2100 :output 30}))]
        (let [res (core/run-turns {:cwd dir :home dir :env (:env env) :max-turns 2}
                                  [{:role "user" :content [{:type "text" :text "hi"}]}])]
          (is (= 2100 (get-in res [:last-usage :input]))
              "last-usage is the size of the final prompt")
          (is (= 2100 (get-in res [:usage :input]))
              "one reply, so the sum and the last call agree")))
      (finally (fs/delete-tree dir)))))

(deftest the-sum-and-the-last-call-differ-after-a-tool-round
  (let [dir (str (fs/create-temp-dir {:prefix "bba-context"}))
        replies (atom [{:role "assistant"
                        :content [{:type "tool_use" :id "1" :name "execute_form"
                                   :input {:code "(+ 1 1)"}}]
                        :stop_reason "tool_use"
                        :usage {:input 1000 :output 20}}
                       {:role "assistant" :content [{:type "text" :text "ok"}]
                        :stop_reason "end_turn" :usage {:input 1600 :output 5}}])]
    (try
      (with-redefs [ext/provider (constantly (fn [_] (let [r (first @replies)] (swap! replies rest) r)))]
        (let [res (core/run-turns {:cwd dir :home dir :env (:env env) :max-turns 3}
                                  [{:role "user" :content [{:type "text" :text "hi"}]}])]
          (is (= 1600 (get-in res [:last-usage :input]))
              "the last prompt, which is what the next turn sends")
          (is (= 2600 (get-in res [:usage :input]))
              "the turn total, which is what the turn cost")))
      (finally (fs/delete-tree dir)))))
