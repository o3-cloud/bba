(ns codex-test
  "The Codex provider: reading ChatGPT credentials from the Codex CLI, translation to
  the Responses API, stream folding, and the HTTP call against a local server."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [org.httpkit.server :as server]
            [bba.codex-auth :as codex-auth]
            [bba.main :as main]
            [bba.provider :as provider]
            [bba.responses :as responses]))

;; ---------------------------------------------------------------- credentials

(defn- b64url [s]
  (.encodeToString (java.util.Base64/getUrlEncoder) (.getBytes (str s) "UTF-8")))

(defn- jwt
  "A readable (not signed) JWT whose claims are `m`."
  [m]
  (str (b64url "{\"alg\":\"RS256\"}") "." (b64url (json/generate-string m)) ".sig"))

(def ^:private now (quot (System/currentTimeMillis) 1000))

(defn- good-claims [exp]
  {"exp" exp
   "https://api.openai.com/auth" {"chatgpt_account_id" "acct-1"
                                   "chatgpt_plan_type" "plus"}})

(defn- live-token [] (jwt (good-claims (+ now 3600))))

(deftest auth-file-uses-codex-home
  (is (str/ends-with? (codex-auth/auth-file {"CODEX_HOME" "/tmp/ch"}) "/tmp/ch/auth.json"))
  (is (str/ends-with? (codex-auth/auth-file {"CODEX_HOME" "/tmp/ch/"}) "/tmp/ch//auth.json") "trailing slash is the caller's business")
  (is (str/ends-with? (codex-auth/auth-file {}) "/.codex/auth.json")))

(deftest reading-jwt-claims
  (let [t (live-token)]
    (is (= "plus" (codex-auth/plan t)))
    (is (= "acct-1" (codex-auth/account-id t)))
    (is (= (+ now 3600) (codex-auth/expiry t)))
    (is (false? (codex-auth/expired? t))))
  (is (true? (codex-auth/expired? (jwt (good-claims (- now 60))))))
  (testing "a token that is not a JWT tells us nothing"
    (is (nil? (codex-auth/expiry "not-a-jwt")))
    (is (nil? (codex-auth/expired? "not-a-jwt")))
    (is (nil? (codex-auth/claims "not-a-jwt")))))

(deftest access-token-from-env-then-from-the-codex-file
  (is (= "from-env" (codex-auth/access-token {"CODEX_ACCESS_TOKEN" "from-env"})))
  (let [dir (str (fs/create-temp-dir {:prefix "bba-codex"}))]
    (try
      (fs/create-dirs (str dir "/.codex"))
      (spit (str dir "/.codex/auth.json")
            (json/generate-string {:auth_mode "chatgpt"
                                   :tokens {:access_token "from-file" :account_id "a"}}))
      (is (= "from-file" (codex-auth/access-token {"CODEX_HOME" (str dir "/.codex")})))
      (testing "the env var wins over the file"
        (is (= "from-env" (codex-auth/access-token {"CODEX_HOME" (str dir "/.codex")
                                                      "CODEX_ACCESS_TOKEN" "from-env"}))))
      (finally (fs/delete-tree dir))))
  (testing "no file at all"
    (is (nil? (codex-auth/access-token {"CODEX_HOME" "/nonexistent-codex-home"})))))

(deftest check-env-for-codex
  (testing "no subscription anywhere"
    (let [msg (provider/check-env {"BBA_PROVIDER" "codex"
                                   "CODEX_HOME" "/nonexistent-codex-home"})]
      (is (str/includes? msg "no ChatGPT subscription"))
      (is (str/includes? msg "codex login"))))
  (testing "an expired token says how to renew it"
    (is (str/includes? (provider/check-env {"BBA_PROVIDER" "codex"
                                            "CODEX_ACCESS_TOKEN" (jwt (good-claims (- now 60)))})
                       "expired")))
  (testing "a live token is fine, and codex needs no API key"
    (is (nil? (provider/check-env {"BBA_PROVIDER" "codex"
                                   "CODEX_ACCESS_TOKEN" (live-token)})))))

;; ---------------------------------------------------------------- translation

(def conversation
  [{:role "user" :content [{:type "text" :text "say hi"}]}
   {:role "assistant" :content [{:type "text" :text "ok"}
                                  {:type "tool_use" :id "t1" :name "bash" :input {:command "echo hi"}}]}
   {:role "user" :content [{:type "tool_result" :tool_use_id "t1" :content "hi\n"}]}])

(deftest messages-to-responses-input
  (is (= [{:type "message" :role "user" :content [{:type "input_text" :text "say hi"}]}
          {:type "message" :role "assistant" :content [{:type "output_text" :text "ok"}]}
          {:type "function_call" :call_id "t1" :name "bash"
           :arguments "{\"command\":\"echo hi\"}"}
          {:type "function_call_output" :call_id "t1" :output "hi\n"}]
         (responses/->input conversation)))
  (testing "string content, an empty assistant turn, and a tool result with no text"
    (is (= [{:type "message" :role "user" :content [{:type "input_text" :text "x"}]}
            {:type "function_call" :call_id "a" :name "read" :arguments "{}"}]
           (responses/->input [{:role "user" :content "x"}
                               {:role "assistant" :content [{:type "tool_use" :id "a" :name "read"}]}])))))

(deftest tools-are-flat-not-nested
  (is (= [{:type "function" :name "bash" :description "Run" :parameters {:type "object"}}]
         (responses/->tools [{:name "bash" :description "Run" :input_schema {:type "object"}}])))
  (testing "a tool with no description does not send null"
    (is (= [{:type "function" :name "b" :parameters {}}]
           (responses/->tools [{:name "b" :input_schema {}}])))))

(deftest build-request-always-streams
  (let [r (responses/build-request {:messages conversation :system "sys"
                                    :tools [{:name "bash" :input_schema {:type "object"}}]}
                                   {:url "https://example.test/codex" :access-token "tok"
                                    :account-id "acct-1" :model "gpt-5.6-luna"})]
    (is (= "https://example.test/codex/responses" (:uri r)))
    (is (= "Bearer tok" (get-in r [:headers "authorization"])))
    (is (= "acct-1" (get-in r [:headers "chatgpt-account-id"])))
    (is (= "gpt-5.6-luna" (get-in r [:body :model])))
    (is (= "sys" (get-in r [:body :instructions])) "the system prompt is top level, not a message")
    (is (true? (get-in r [:body :stream])) "the endpoint rejects stream=false")
    (is (false? (get-in r [:body :store])))
    (is (= responses/default-max-tokens (get-in r [:body :max_output_tokens])))
    (is (= "bash" (get-in r [:body :tools 0 :name]))))
  (testing "no account id in the token, no header"
    (is (nil? (get-in (responses/build-request {:messages []} {:url "u" :access-token "t" :model "m"})
                      [:headers "chatgpt-account-id"])))))

;; ---------------------------------------------------------------- stream folding

(deftest folding-a-stream
  (testing "text deltas are appended and passed to on-text"
    (let [seen (atom [])
          acc (reduce (fn [a e] (responses/chunk-step a e (fn [s] (swap! seen conj s))))
                      responses/stream-init
                      [{:type "response.output_text.delta" :delta "Hel"}
                       {:type "response.output_text.delta" :delta "lo"}
                       {:type "response.completed"}])]
      (is (= "Hello" (:text acc)))
      (is (= ["Hel" "lo"] @seen))))
  (testing "a tool call arrives as added + deltas + done"
    (is (= {:role "assistant" :stop_reason "tool_use"
            :content [{:type "tool_use" :id "call_a" :name "get_weather" :input {:city "Paris"}}]}
           (responses/reply-of-stream
            (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                    [{:type "response.output_item.added" :output_index 0
                      :item {:type "function_call" :call_id "call_a" :name "get_weather" :arguments ""}}
                     {:type "response.function_call_arguments.delta" :output_index 0 :delta "{\"city\":"}
                     {:type "response.function_call_arguments.delta" :output_index 0 :delta "\"Paris\"}"}
                     {:type "response.function_call_arguments.done" :output_index 0
                      :arguments "{\"city\":\"Paris\"}"}
                     {:type "response.output_item.done" :output_index 0
                      :item {:type "function_call" :call_id "call_a" :name "get_weather"
                             :arguments "{\"city\":\"Paris\"}"}}])))))
  (testing "a tool call that arrives only as output_item.done still works"
    (is (= [{:type "tool_use" :id "c9" :name "bash" :input {:command "pwd"}}]
           (:content (responses/reply-of-stream
                      (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                              [{:type "response.output_item.done" :output_index 0
                                :item {:type "function_call" :call_id "c9" :name "bash"
                                       :arguments "{\"command\":\"pwd\"}"}}])))))))

(deftest folding-edge-cases
  (testing "bad JSON arguments become an empty input, not an error"
    (is (= [{:type "tool_use" :id "c1" :name "b" :input {}}]
           (:content (responses/reply-of-stream
                      (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                              [{:type "response.output_item.done" :output_index 0
                                :item {:type "function_call" :call_id "c1" :name "b"
                                       :arguments "{oops"}}]))))))
  (testing "a missing call_id gets a generated one"
    (is (= "call_0" (-> (responses/reply-of-stream
                           (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                                   [{:type "response.output_item.done" :output_index 0
                                     :item {:type "function_call" :name "b" :arguments "{}"}}]))
                          :content first :id))))
  (testing "a text-only turn ends the turn"
    (is (= "end_turn" (:stop_reason (responses/reply-of-stream
                                       (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                                               [{:type "response.output_text.delta" :delta "done"}]))))))
  (testing "unknown events are ignored"
    (is (= responses/stream-init (responses/chunk-step responses/stream-init
                                                         {:type "response.in_progress"} nil))))
  (testing "an incomplete stream with no answer throws instead of returning nothing"
    (let [acc (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                      [{:type "response.created"}
                       {:type "response.incomplete"
                        :response {:status "incomplete"
                                   :incomplete_details {:reason "max_output_tokens"}}}])]
      (is (= "max_output_tokens" (:incomplete acc)) "the reason is recorded")
      (is (str/includes? (try (responses/reply-of-stream acc) nil
                              (catch Exception e (ex-message e)))
                         "ran out of room")
          "and the caller is told, not handed an empty answer")))
  (testing "incomplete with a real answer is kept, not thrown away"
    (let [acc (reduce #(responses/chunk-step %1 %2 nil) responses/stream-init
                      [{:type "response.output_text.delta" :delta "partial answer"}
                       {:type "response.incomplete"
                        :response {:incomplete_details {:reason "max_output_tokens"}}}])]
      (is (= [{:type "text" :text "partial answer"}] (:content (responses/reply-of-stream acc))))))
  (testing "an error event throws"
    (is (= "API error: rate limited"
           (try (responses/chunk-step responses/stream-init
                                      {:type "error" :error {:message "rate limited"}} nil)
                (catch Exception e (ex-message e)))))
    (is (str/starts-with? (try (responses/chunk-step responses/stream-init
                                                       {:type "response.failed"
                                                        :response {:error {:message "boom"}}} nil)
                               (catch Exception e (ex-message e)))
                          "API error: boom"))))

;; ---------------------------------------------------------------- HTTP

(defn- sse
  "A server-sent-events body from maps."
  [events]
  (str/join (map (fn [e] (str "event: " (:type e) "\ndata: " (json/generate-string e) "\n\n"))
                 events)))

(defn- with-server
  "Run (f base-url) against a local server that answers `body`. Returns [result requests]."
  [status body f]
  (let [reqs (atom [])
        stop (server/run-server
              (fn [r] (swap! reqs conj {:uri (:uri r) :headers (:headers r)
                                        :body (json/parse-string (slurp (:body r)) true)})
                {:status status :headers {"content-type" "text/event-stream"} :body body})
              {:port 0 :legacy-return-value? false})
        base (str "http://127.0.0.1:" (server/server-port stop))]
    (try [(f base) @reqs] (finally (server/server-stop! stop)))))

(def tool-stream
  (sse [{:type "response.created"}
        {:type "response.output_item.added" :output_index 0
         :item {:type "function_call" :call_id "c9" :name "bash" :arguments ""}}
        {:type "response.function_call_arguments.delta" :output_index 0 :delta "{\"command\":"}
        {:type "response.function_call_arguments.delta" :output_index 0 :delta "\"pwd\"}"}
        {:type "response.function_call_arguments.done" :output_index 0
         :arguments "{\"command\":\"pwd\"}"}
        {:type "response.output_item.done" :output_index 0
         :item {:type "function_call" :call_id "c9" :name "bash" :arguments "{\"command\":\"pwd\"}"}}
        {:type "response.completed"}]))

(def req {:messages conversation :system "sys"
          :tools [{:name "bash" :description "Run" :input_schema {:type "object"}}]})

(defn- codex-call
  "A provider fn for `env` with the base URL filled in; use with with-server."
  [env base] ((provider/codex (assoc env "CODEX_BASE_URL" base)) req))

(deftest codex-over-http
  (let [[r [{:keys [uri headers body]}]]
        (with-server 200 tool-stream (fn [base] (codex-call {"CODEX_ACCESS_TOKEN" (live-token)} base)))]
    (is (= "/responses" uri))
    (is (= (str "Bearer " (live-token)) (get headers "authorization")))
    (is (= "acct-1" (get headers "chatgpt-account-id")))
    (is (= "gpt-5.6-luna" (:model body)) "the Codex default model")
    (is (= "sys" (:instructions body)))
    (is (true? (:stream body)))
    (is (= "function_call_output" (:type (last (:input body))))
        "the tool result is its own item, not a role message")
    (is (= "t1" (:call_id (last (:input body)))) "it refers back to the call it answers")
    (is (= [{:type "tool_use" :id "c9" :name "bash" :input {:command "pwd"}}] (:content r))
        "and the reply's new call keeps the server's call_id"))
  (testing "the model can be overridden"
    (let [[_ [{:keys [body]}]] (with-server 200 (sse [{:type "response.output_text.delta" :delta "ok"}])
                                 (fn [base] (codex-call {"CODEX_ACCESS_TOKEN" (live-token)
                                                         "BBA_MODEL" "gpt-6-astra"} base)))]
      (is (= "gpt-6-astra" (:model body)))))
  (testing "streamed text reaches the answer"
    (let [[r] (with-server 200 (sse [{:type "response.output_text.delta" :delta "Hel"}
                                     {:type "response.output_text.delta" :delta "lo"}])
                                    (fn [base] (codex-call {"CODEX_ACCESS_TOKEN" (live-token)} base)))]
      (is (= [{:type "text" :text "Hello"}] (:content r)))))
  (testing "an HTTP error becomes 'API error <status>: <msg>'"
    (let [[e] (with-server 401 "{\"detail\":\"Invalid token\"}"
                (fn [base]
                  (try (codex-call {"CODEX_ACCESS_TOKEN" (live-token)} base)
                       nil
                       (catch Exception e e))))]
      (is (= "API error 401: {\"detail\":\"Invalid token\"}" (ex-message e))))))

(deftest from-env-selects-codex
  (let [[_ [{:keys [uri]}]] (with-server 200 (sse [{:type "response.output_text.delta" :delta "hi"}])
                              (fn [base]
                                ((provider/from-env {"BBA_PROVIDER" "CODEX"
                                                     "CODEX_ACCESS_TOKEN" (live-token)
                                                     "CODEX_BASE_URL" base}) req)))]
    (is (= "/responses" uri) "case does not matter")))

(deftest codex-needs-no-api-key-to-be-selected
  (is (nil? (provider/check-env {"BBA_PROVIDER" "codex" "CODEX_ACCESS_TOKEN" (live-token)}))))

;; ---------------------------------------------------------------- end to end

(deftest codex-runs-a-turn-end-to-end
  (let [dir (str (fs/create-temp-dir {:prefix "bba-codex"}))]
    (try
      (let [out (java.io.StringWriter.)
            err (java.io.StringWriter.)
            run-it (fn [base]
                     (binding [*out* out *err* err]
                       (main/run {:args ["--provider" "codex" "-p" "hi"] :cwd dir
                                  :env {"CODEX_ACCESS_TOKEN" (live-token) "CODEX_BASE_URL" base
                                        "BBA_HOME" (str dir "/home")} :in nil})))
            [_ reqs] (with-server 200 (sse [{:type "response.output_text.delta" :delta "hello there"}])
                       run-it)]
        (is (str/includes? (str out) "hello there"))
        (is (= "/responses" (:uri (first reqs))))
        (is (str/starts-with? (-> reqs first :body :instructions) "You are")))
      (finally (fs/delete-tree dir)))))
