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
            {:role "assistant" :content nil
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
  (is (= "ANTHROPIC_API_KEY is not set" (provider/check-env {})))
  (is (nil? (provider/check-env {"ANTHROPIC_API_KEY" "k"})))
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

(deftest switch-commands-show-and-validate
  (let [{:keys [code out err]} (session {"ANTHROPIC_API_KEY" "k"}
                                        ["/provider" "/model" "/provider ollama" "/model qwen3.5:0.8b"
                                         "/provider nope" "/provider openai" "/model" "/quit"])]
    (is (= 0 code))
    (is (str/includes? out "provider: anthropic, model: claude-opus-5-5") "banner and /provider show the start state")
    (is (str/includes? out "providers: anthropic, ollama, openai, openrouter"))
    (is (str/includes? out "provider: ollama, model: gpt-oss") "switch uses the new default model")
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
