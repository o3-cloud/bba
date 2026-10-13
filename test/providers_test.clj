(ns providers-test
  "OpenAI and Ollama providers: translation units, selection, and HTTP against a local server."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.fs :as fs]
            [org.httpkit.server :as server]
            [bba.ext :as ext]
            [bba.main :as main]
            [bba.openai :as openai]
            [bba.tui :as tui]
            [bba.provider :as provider]))

;; ---------------------------------------------------------------- translation

(def conversation
  [{:role "user" :content [{:type "text" :text "say hi"}]}
   {:role "assistant" :content [{:type "text" :text "ok"}
                                {:type "tool_use" :id "t1" :name "bash" :input {:command "echo hi"}}]}
   {:role "user" :content [{:type "tool_result" :tool_use_id "t1" :content "hi\n"}]}])

(deftest messages-to-chat-completions
  (is (= [{:role "system" :content "sys"}
          {:role "user" :content "say hi"}
          {:role "assistant" :content "ok"
           :tool_calls [{:id "t1" :type "function"
                         :function {:name "bash" :arguments "{\"command\":\"echo hi\"}"}}]}
          {:role "tool" :tool_call_id "t1" :content "hi\n"}]
         (openai/->messages "sys" conversation)))
  (testing "string content, no system, assistant with only a tool call"
    (is (= [{:role "user" :content "x"}
            {:role "assistant" :content ""
             :tool_calls [{:id "a" :type "function" :function {:name "read" :arguments "{}"}}]}]
           (openai/->messages "" [{:role "user" :content "x"}
                                  {:role "assistant" :content [{:type "tool_use" :id "a" :name "read"}]}])))))

(deftest tools-to-functions
  (is (= [{:type "function" :function {:name "bash" :description "Run" :parameters {:type "object"}}}]
         (openai/->tools [{:name "bash" :description "Run" :input_schema {:type "object"}}]))))

(deftest reply-from-chat-completions
  (testing "text only"
    (is (= {:role "assistant" :stop_reason "end_turn" :content [{:type "text" :text "done"}]}
           (openai/->reply {:choices [{:message {:role "assistant" :content "done"}}]}))))
  (testing "tool call with JSON-string arguments and null content"
    (is (= [{:type "tool_use" :id "c1" :name "bash" :input {:command "ls"}}]
           (:content (openai/->reply {:choices [{:message {:content nil
                                                           :tool_calls [{:id "c1" :type "function"
                                                                         :function {:name "bash" :arguments "{\"command\":\"ls\"}"}}]}}]})))))
  (testing "object arguments, missing id, bad JSON"
    (is (= [{:type "tool_use" :id "call_0" :name "a" :input {:x 1}}
            {:type "tool_use" :id "call_1" :name "b" :input {}}]
           (:content (openai/->reply {:choices [{:message {:tool_calls [{:function {:name "a" :arguments {:x 1}}}
                                                                        {:id "" :function {:name "b" :arguments "{oops"}}]}}]}))))))

;; ---------------------------------------------------------------- selection

(deftest check-env-per-provider
  (is (nil? (provider/check-env {})) "the default provider is ollama, which needs no key")
  (is (= "ANTHROPIC_API_KEY is not set" (provider/check-env {"BBA_PROVIDER" "anthropic"})))
  (is (nil? (provider/check-env {"BBA_PROVIDER" "anthropic" "ANTHROPIC_API_KEY" "k"})))
  (is (= "OPENAI_API_KEY is not set" (provider/check-env {"BBA_PROVIDER" "openai"})))
  (is (nil? (provider/check-env {"BBA_PROVIDER" "OpenAI" "OPENAI_API_KEY" "k"})) "case does not matter")
  (is (nil? (provider/check-env {"BBA_PROVIDER" "ollama"})) "Ollama needs no key")
  (is (= "OPENROUTER_API_KEY is not set" (provider/check-env {"BBA_PROVIDER" "openrouter"})))
  (is (str/starts-with? (provider/check-env {"BBA_PROVIDER" "gemini"}) "unknown provider: gemini")))

(deftest parse-provider-and-model-flags
  (is (= {:provider "ollama" :model "qwen3.5:0.8b" :prompt "x"}
         (main/parse-args ["--provider" "ollama" "--model" "qwen3.5:0.8b" "-p" "x"])))
  (is (:error (main/parse-args ["--provider"]))))

(deftest provider-flag-overrides-env-in-main
  (let [dir (str (fs/create-temp-dir {:prefix "bba-test"}))
        err (java.io.StringWriter.)
        code (binding [*err* err *out* (java.io.StringWriter.)]
               (try (main/run {:args ["--provider" "openai" "-p" "hi"] :cwd dir
                               :env {"ANTHROPIC_API_KEY" "k" "BBA_HOME" "/nonexistent-bba-home"} :in nil})
                    (finally (fs/delete-tree dir))))]
    (is (= 1 code))
    (is (str/includes? (str err) "OPENAI_API_KEY is not set"))))

;; ---------------------------------------------------------------- HTTP

(defn- with-server
  "Run (f base-url) against a local server that answers with `status` and `body`. Returns [result requests]."
  [status body f]
  (let [reqs (atom [])
        stop (server/run-server
              (fn [req] (swap! reqs conj {:uri (:uri req) :headers (:headers req) :body (json/parse-string (slurp (:body req)) true)})
                {:status status :headers {"content-type" "application/json"} :body body})
              {:port 0 :legacy-return-value? false})
        base (str "http://127.0.0.1:" (server/server-port stop))]
    (try [(f base) @reqs] (finally (server/server-stop! stop)))))

(def tool-reply
  (json/generate-string {:choices [{:message {:content nil :tool_calls [{:id "c9" :type "function"
                                                                         :function {:name "bash" :arguments "{\"command\":\"pwd\"}"}}]}}]}))

(def req {:messages conversation :system "sys" :tools [{:name "bash" :description "Run" :input_schema {:type "object"}}]})

(deftest openai-over-http
  (let [[r [{:keys [uri headers body]}]]
        (with-server 200 tool-reply #((provider/openai {"OPENAI_API_KEY" "sk-test" "OPENAI_BASE_URL" (str % "/v1/")}) req))]
    (is (= "/v1/chat/completions" uri))
    (is (= "Bearer sk-test" (get headers "authorization")))
    (is (= "gpt-5" (:model body)))
    (is (= "tool" (:role (last (:messages body)))))
    (is (= "bash" (get-in body [:tools 0 :function :name])))
    (is (= [{:type "tool_use" :id "c9" :name "bash" :input {:command "pwd"}}] (:content r))))
  (testing "error body becomes 'API error <status>: <msg>'"
    (let [[e] (with-server 401 "{\"error\":{\"message\":\"Incorrect API key provided\"}}"
                #(try ((provider/openai {"OPENAI_API_KEY" "bad" "OPENAI_BASE_URL" %}) req) nil
                      (catch Exception e e)))]
      (is (= "API error 401: Incorrect API key provided" (ex-message e))))))

(deftest openrouter-over-http
  (let [[r [{:keys [uri headers body]}]]
        (with-server 200 "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"
          #((provider/from-env {"BBA_PROVIDER" "openrouter" "OPENROUTER_API_KEY" "or-key"
                                "OPENROUTER_BASE_URL" (str % "/api/v1")}) req))]
    (is (= "/api/v1/chat/completions" uri))
    (is (= "Bearer or-key" (get headers "authorization")))
    (is (= "anthropic/claude-opus-5.5" (:model body)))
    (is (= "hi" (-> r :content first :text))))
  (testing "error inside an HTTP 200 body"
    (let [[e] (with-server 200 "{\"error\":{\"message\":\"No endpoints found\",\"code\":404}}"
                #(try ((provider/openrouter {"OPENROUTER_API_KEY" "k" "OPENROUTER_BASE_URL" %}) req) nil
                      (catch Exception e e)))]
      (is (= "API error: No endpoints found" (ex-message e))))))

(deftest ollama-over-http
  (let [host-no-scheme #(str/replace % "http://" "")
        [r [{:keys [uri headers body]}]]
        (with-server 200 "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}"
          #((provider/ollama {"OLLAMA_HOST" (host-no-scheme %) "BBA_MODEL" "qwen3.5:0.8b"}) req))]
    (is (= "/v1/chat/completions" uri))
    (is (nil? (get headers "authorization")) "no key sent")
    (is (= "qwen3.5:0.8b" (:model body)))
    (is (= "hello" (-> r :content first :text))))
  (testing "server not running gives a readable error"
    (let [e (try ((provider/ollama {"OLLAMA_HOST" "127.0.0.1:1"}) req) nil (catch Exception e e))]
      (is (= "cannot connect to http://127.0.0.1:1/v1/chat/completions" (ex-message e))))))

;; ---------------------------------------------------------------- /provider and /model

(use-fixtures :each (fn [t] (ext/reset-all!) (t) (ext/reset-all!)))

(defn- session
  "Run an interactive session with `lines` as input. Returns {:code :out :err}."
  [env lines]
  (let [dir (str (fs/create-temp-dir {:prefix "bba-test"}))
        out (java.io.StringWriter.) err (java.io.StringWriter.)]
    (try
      (let [code (binding [*out* out *err* err]
                   (main/run {:args [] :cwd dir :env (assoc env "BBA_HOME" (str dir "/home"))
                              :in (java.io.BufferedReader. (java.io.StringReader. (str (str/join "\n" lines) "\n")))}))]
        {:code code :out (str out) :err (str err)})
      (finally (fs/delete-tree dir)))))

(deftest default-provider-is-ollama-deepseek
  (let [{:keys [code out]} (session {}
                                    ["/provider" "/quit"])]
    (is (= 0 code))
    (is (str/includes? out "provider: ollama, model: deepseek-v4.1-flash:cloud")
        "with no BBA_PROVIDER the banner and /provider show the new default")
    (is (nil? (provider/check-env {})) "and it runs with no key at all")))

(deftest switch-commands-show-and-validate
  (let [{:keys [code out err]} (session {"BBA_PROVIDER" "anthropic" "ANTHROPIC_API_KEY" "k"}
                                        ["/provider" "/model" "/provider ollama" "/model qwen3.5:0.8b"
                                         "/provider nope" "/provider openai" "/model" "/quit"])]
    (is (= 0 code))
    (is (str/includes? out "provider: anthropic, model: claude-opus-5-5") "banner and /provider show the start state")
    (is (str/includes? out "providers: anthropic, codex, ollama, openai, openrouter"))
    (is (str/includes? out "provider: ollama, model: deepseek-v4.1-flash:cloud") "switch uses the new default model")
    (is (str/includes? out "provider: ollama, model: qwen3.5:0.8b"))
    (is (str/includes? err "unknown provider: nope"))
    (is (str/includes? err "OPENAI_API_KEY is not set; still using ollama"))
    (is (= 2 (count (re-seq #"model: qwen3.5:0.8b" out))) "a failed switch keeps the model")))

(deftest switch-reaches-the-next-request
  (let [[{:keys [code out]} reqs]
        (with-server 200 "{\"choices\":[{\"message\":{\"content\":\"from-ollama\"}}]}"
          #(session {"ANTHROPIC_API_KEY" "k" "OLLAMA_HOST" %}
                    ["/provider ollama" "/model m1" "hi" "/model m2" "again" "/quit"]))]
    (is (= 0 code))
    (is (str/includes? out "from-ollama"))
    (is (= ["m1" "m2"] (map (comp :model :body) reqs)))
    (is (= "/v1/chat/completions" (:uri (first reqs))))
    (testing "history is kept across the switch"
      (is (= ["hi" "from-ollama" "again"] (map :content (rest (:messages (:body (second reqs))))))))))

(deftest provider-command-takes-an-optional-model
  (let [{:keys [code out]} (session {"ANTHROPIC_API_KEY" "k" "OPENAI_API_KEY" "k2"}
                                    ["/provider openai gpt-x" "/provider" "/quit"])]
    (is (= 0 code))
    (testing "both name and model are applied"
      (is (str/includes? out "provider: openai, model: gpt-x")))
    (testing "the model survives a later /provider with no args"
      (is (str/includes? out "provider: openai, model: gpt-x")))))

(deftest new-starts-a-fresh-session
  (let [[{:keys [code out]} reqs]
        (with-server 200 "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
          #(session {"ANTHROPIC_API_KEY" "k" "OLLAMA_HOST" %}
                    ["/provider ollama" "hi" "/new" "again" "/quit"]))]
    (is (= 0 code))
    (is (str/includes? out "new session"))
    (is (= 2 (count (distinct (re-seq #"sessions/\S+\.jsonl" out)))) "banner and /new print different session files")
    (testing "the conversation is empty after /new"
      (is (= ["again"] (map :content (rest (:messages (:body (second reqs))))))))))

;; ---------------------------------------------------------------- model catalogs and pickers

(deftest parse-model-catalogs
  (is (= ["alpha" "beta"]
         (provider/catalog-models :codex
                                  {:fetched_at "date" :models [{:slug "beta" :display_name "Label" :visibility "list"
                                                                :supported_in_api false}
                                                               {:slug "alpha"} {:slug "beta"}
                                                               {:slug "hidden" :visibility "hide"}
                                                               {:slug ""} {:slug 1} nil]})))
  (is (= ["a:latest" "b:q4"]
         (provider/catalog-models :ollama {:models [{:name "b:q4"} {:model "a:latest"} {:name "b:q4"} {}]})))
  (doseq [data [nil [] {:models {}} {:models "bad"} {:models [1 "x" nil {}]}]]
    (is (= [] (provider/catalog-models :codex data)))
    (is (= [] (provider/catalog-models :ollama data)))))

(deftest codex-cache-and-fallback
  (let [dir (fs/create-temp-dir {:prefix "bba-catalog"})
        file (str (fs/path dir "models_cache.json"))
        env {"BBA_PROVIDER" "codex" "CODEX_HOME" (str dir) "BBA_MODEL" "custom"}
        fallback ["custom" (:model (provider/providers "codex"))]]
    (try
      (is (= fallback (provider/model-catalog env)) "missing cache")
      (spit file (json/generate-string {:models [{:slug "from-cache" :visibility "list"}]}))
      (is (= ["custom" "from-cache"] (provider/model-catalog env)))
      (doseq [body ["{bad" "null" "{}" "{\"models\":[]}"]]
        (spit file body)
        (is (= fallback (provider/model-catalog env))))
      (finally (fs/delete-tree dir))))
  (doseq [name ["openai" "anthropic" "openrouter"]]
    (let [default (:model (provider/providers name))]
      (is (= [default] (provider/model-catalog {"BBA_PROVIDER" name})))
      (is (= ["custom" default] (provider/model-catalog {"BBA_PROVIDER" name "BBA_MODEL" "custom"}))))))

(deftest ollama-catalog-http-and-failures
  (doseq [[status body expected] [[200 "{\"models\":[{\"name\":\"local:q4\"}]}" ["custom" "local:q4"]]
                                  [200 "bad" ["custom" provider/default-model]]
                                  [500 "{}" ["custom" provider/default-model]]]]
    (let [seen (atom nil)
          srv (server/run-server (fn [req] (reset! seen (:uri req)) {:status status :body body})
                                 {:port 0 :legacy-return-value? false})]
      (try
        (is (= expected (provider/model-catalog {"OLLAMA_HOST" (str "127.0.0.1:" (server/server-port srv) "/")
                                                 "BBA_MODEL" "custom"})))
        (is (= "/api/tags" @seen))
        (finally (server/server-stop! srv)))))
  (is (= [provider/default-model] (provider/model-catalog {"OLLAMA_HOST" "http://127.0.0.1:1"}))))

(deftest ollama-catalog-timeout
  (let [srv (server/run-server #(server/as-channel % {:on-open (fn [_])})
                               {:port 0 :legacy-return-value? false})
        start (System/nanoTime)]
    (try
      (is (= [provider/default-model]
             (provider/model-catalog {"OLLAMA_HOST" (str "http://127.0.0.1:" (server/server-port srv))})))
      (is (< (/ (- (System/nanoTime) start) 1e6) 4000) "a stalled server cannot block the prompt")
      (finally (server/server-stop! srv)))))

(deftest interactive-picker-commands
  (let [ctx {:tty? true :env {"BBA_PROVIDER" "ollama" "BBA_MODEL" "old" "OPENAI_API_KEY" "k"}}
        seen (atom [])
        choose (fn [value] (fn [& args] (swap! seen conj args) value))]
    (with-redefs [tui/pick! (choose "openai")]
      (let [result (#'main/switch-provider ctx "")]
        (is (= "openai" (provider/provider-name (:env result))))
        (is (= "gpt-5" (provider/model-name (:env result))))
        (is (= ["Select a provider" (sort (keys provider/providers)) "ollama"] (take 3 (first @seen))))
        (is (= 1 (count @seen)) "openai has one model, so no model list follows")
        (let [{:keys [notes muted]} (last (first @seen))]
          (is (= "old" (notes "ollama")) "the current provider shows its current model")
          (is (= "gpt-5" (notes "openai")))
          (is (= "ANTHROPIC_API_KEY is not set" (notes "anthropic")))
          (is (contains? muted "anthropic"))
          (is (not (contains? muted "openai"))))))
    (with-redefs [tui/pick! (fn [title & _] (swap! seen conj [title]) (if (= title "Select a provider") "ollama" "m2"))
                  provider/model-catalog (constantly ["m1" "m2"])]
      (let [ctx' (assoc-in ctx [:env "BBA_PROVIDER"] "openai")]
        (is (= ["ollama" "m2"] ((juxt provider/provider-name provider/model-name) (:env (#'main/switch-provider ctx' ""))))
            "a new provider opens its model list next")
        (is (= ["Select a model for ollama"] (last @seen)))))
    (with-redefs [tui/pick! (fn [title & _] (when (= title "Select a provider") "ollama"))
                  provider/model-catalog (constantly ["m1" "m2"])]
      (is (= provider/default-model
             (provider/model-name (:env (#'main/switch-provider (assoc-in ctx [:env "BBA_PROVIDER"] "openai") ""))))
          "Esc in the model list keeps the default model"))
    (with-redefs [tui/pick! (choose "anthropic")]
      (is (= ctx (#'main/switch-provider ctx nil)) "credential validation still applies"))
    (with-redefs [tui/pick! (choose "new") provider/model-catalog (constantly ["old" "new"])]
      (is (= "new" (get-in (#'main/switch-model ctx "") [:env "BBA_MODEL"])))
      (is (= ["Select a model for ollama" ["old" "new"] "old"] (take 3 (last @seen)))))
    (with-redefs [tui/pick! (choose nil) provider/model-catalog (constantly ["old"])]
      (is (= ctx (#'main/switch-provider ctx "")))
      (is (= ctx (#'main/switch-model ctx ""))))
    (with-redefs [tui/pick! (fn [& _] (throw (ex-info "unexpected picker" {})))
                  provider/model-catalog (constantly ["old" "extra"])]
      (is (= "explicit" (get-in (#'main/switch-provider ctx "openai explicit") [:env "BBA_MODEL"])))
      (is (= "old" (get-in (#'main/switch-provider ctx "ollama") [:env "BBA_MODEL"])))
      (is (= "direct" (get-in (#'main/switch-model ctx "direct") [:env "BBA_MODEL"])))
      (let [plain (assoc ctx :tty? false)
            out (with-out-str
                  (is (= plain (#'main/switch-provider plain "")))
                  (is (= plain (#'main/switch-model plain ""))))]
        (is (str/includes? out "providers: anthropic, codex, ollama, openai, openrouter"))
        (is (str/includes? out "models: old, extra"))))))

;; ---------------------------------------------------------------- usage and cost

(deftest usage-from-each-api
  (testing "Anthropic: the stream's start and end are merged; cached input counts as input"
    (let [acc (-> {:blocks (sorted-map)}
                  (provider/anthropic-step {:type "message_start"
                                            :message {:usage {:input_tokens 10 :cache_read_input_tokens 90
                                                              :output_tokens 1}}} nil)
                  (provider/anthropic-step {:type "message_delta" :delta {:stop_reason "end_turn"}
                                            :usage {:output_tokens 42}} nil))]
      (is (= {:input 100 :output 42} (provider/anthropic-usage (get-in acc [:message :usage]))))
      (is (= "end_turn" (get-in acc [:message :stop_reason])))
      (is (nil? (provider/anthropic-usage nil)))))
  (testing "Chat Completions: the last stream chunk, and OpenRouter's cost"
    (let [acc (-> openai/stream-init
                  (openai/chunk-step {:choices [{:delta {:content "hi"}}]} nil)
                  (openai/chunk-step {:choices [] :usage {:prompt_tokens 7 :completion_tokens 3 :cost 0.002}} nil))]
      (is (= {:input 7 :output 3 :cost 0.002} (:usage (openai/->reply (openai/stream-body acc)))))
      (is (= "hi" (get-in (openai/->reply (openai/stream-body acc)) [:content 0 :text]))))
    (is (nil? (:usage (openai/->reply {:choices [{:message {:content "x"}}]}))) "no usage, no map"))
  (testing "a turn sums the usage of its requests"
    (is (= {:input 3 :output 5} (provider/add-usage {:input 1 :output 2} {:input 2 :output 3})))
    (is (= {:input 1 :output 2} (provider/add-usage nil {:input 1 :output 2})))))

(deftest cost-from-the-provider-or-bba-price
  (let [u {:input 2000000 :output 1000000}]
    (is (= 0.5 (provider/usage-cost {"BBA_PRICE" "9,9"} (assoc u :cost 0.5))) "the provider's figure wins")
    (is (= 21.0 (provider/usage-cost {"BBA_PRICE" " 3 , 15 "} u)) "dollars per million tokens")
    (is (nil? (provider/usage-cost {} u)))
    (is (nil? (provider/usage-cost {"BBA_PRICE" "cheap"} u)) "a bad price shows no cost")))
