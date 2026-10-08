(ns bba.provider
  "Built-in providers: Anthropic (default), OpenAI, OpenRouter, Ollama and Codex, which
  uses a ChatGPT subscription. Pick one with BBA_PROVIDER."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [bba.codex-auth :as codex-auth]
            [bba.openai :as openai]
            [bba.responses :as responses]))

(def api-url "https://api.anthropic.com/v1/messages")
(def default-model "claude-opus-5-5")

(def providers
  "name -> {:key env var that must be set, :model default, :url default base, :url-env override,
  :path appended to the base}. All but anthropic use the Chat Completions API."
  {"anthropic"  {:key "ANTHROPIC_API_KEY" :model default-model}
   "openai"     {:key "OPENAI_API_KEY" :model "gpt-5" :url "https://api.openai.com/v1" :url-env "OPENAI_BASE_URL"
                 :path "/chat/completions"}
   "openrouter" {:key "OPENROUTER_API_KEY" :model "anthropic/claude-opus-5.5" :url "https://openrouter.ai/api/v1"
                 :url-env "OPENROUTER_BASE_URL" :path "/chat/completions"}
   "ollama"     {:model "gpt-oss" :url "http://localhost:11434" :url-env "OLLAMA_HOST" :path "/v1/chat/completions"}
   ;; Codex uses a ChatGPT subscription, not a key: no :key, and bba reads the
   ;; Codex CLI login. Its endpoint speaks the Responses API, not Chat Completions.
   "codex"      {:model "gpt-5.6-luna" :url "https://chatgpt.com/backend-api/codex"
                 :url-env "CODEX_BASE_URL" :api :responses}})

(defn provider-name [env] (str/lower-case (or (not-empty (get env "BBA_PROVIDER")) "anthropic")))

(defn check-env
  "nil when the selected provider can run, else an error message."
  [env]
  (let [n (provider-name env) {:keys [key] :as p} (providers n)]
    (cond
      (nil? p)
      (str "unknown provider: " n " (use " (str/join ", " (sort (keys providers))) ")")

      (and key (str/blank? (get env key)))
      (str key " is not set")

      (= n "codex")
      (let [t (codex-auth/access-token env)]
        (cond (str/blank? t)
              (str "no ChatGPT subscription found: sign in with the Codex CLI (codex login), "
                   "then try again. bba reads " (codex-auth/auth-file env)
                   ", or set CODEX_ACCESS_TOKEN")
              (codex-auth/expired? t)
              (str "the ChatGPT subscription token expired: run `codex login` to renew it"))))))

(defn build-request
  "Pure: the HTTP request for one Messages API call."
  [{:keys [messages system tools]} {:keys [api-key model max-tokens]}]
  {:uri api-url
   :headers {"x-api-key" api-key
             "anthropic-version" "2023-06-01"
             "content-type" "application/json"}
   :body (cond-> {:model (or model default-model)
                  :max_tokens (or max-tokens 8192)
                  :system system
                  :messages messages}
           (seq tools) (assoc :tools tools))})

(defn- api-error [status body]
  (let [msg (try (get-in (json/parse-string body true) [:error :message])
                 (catch Exception _ nil))]
    (ex-info (str "API error " status ": " (or msg body)) {:status status})))

(defn- post-json
  "POST the request; return the parsed JSON body or throw a readable error."
  [{:keys [uri headers body]}]
  (let [resp (try (http/post uri {:headers headers :body (json/generate-string body) :throw false})
                  (catch java.net.ConnectException _
                    (throw (ex-info (str "cannot connect to " uri) {}))))]
    (if (<= 200 (:status resp) 299)
      (try (json/parse-string (:body resp) true)
           (catch Exception _ (throw (api-error (:status resp) "response is not JSON"))))
      (throw (api-error (:status resp) (:body resp))))))

(defn- sse-data
  "Lazy seq of the data payloads in a server-sent-events stream."
  [rd]
  (keep #(when (str/starts-with? % "data:") (str/trim (subs % 5))) (line-seq rd)))

(defn- post-stream
  "POST with stream=true; fold each event with (step acc event) from `init`. Returns the folded value."
  [{:keys [uri headers body]} step init]
  (let [resp (try (http/post uri {:headers headers :body (json/generate-string (assoc body :stream true))
                                  :as :stream :throw false})
                  (catch java.net.ConnectException _
                    (throw (ex-info (str "cannot connect to " uri) {}))))]
    (if (<= 200 (:status resp) 299)
      (with-open [rd (io/reader (:body resp))]
        (reduce (fn [acc data] (if (= data "[DONE]") (reduced acc) (step acc (json/parse-string data true))))
                init (sse-data rd)))
      (throw (api-error (:status resp) (slurp (:body resp)))))))

(defn anthropic-step
  "Pure except `on-text`: fold one Messages API stream event into {:message .. :blocks {index block}}."
  [acc {:keys [type index content_block delta message error]} on-text]
  (case type
    "message_start" (assoc acc :message (dissoc message :content))
    "content_block_start" (assoc-in acc [:blocks index]
                                    (cond-> content_block (= "tool_use" (:type content_block)) (assoc :json "")))
    "content_block_delta" (case (:type delta)
                            "text_delta" (do (when on-text (on-text (:text delta)))
                                             (update-in acc [:blocks index :text] str (:text delta)))
                            "input_json_delta" (update-in acc [:blocks index :json] str (:partial_json delta))
                            acc)
    "message_delta" (update acc :message merge delta)
    "error" (throw (ex-info (str "API error: " (:message error)) {}))
    acc))

(defn anthropic-message
  "Folded Anthropic stream -> an assistant message."
  [{:keys [message blocks]}]
  (assoc message :role "assistant"
         :content (mapv (fn [b] (if (contains? b :json)
                                  (-> b (assoc :input (if (str/blank? (:json b)) {} (json/parse-string (:json b) true)))
                                      (dissoc :json))
                                  b))
                        (vals blocks))))

(defn- base-url [env n]
  (let [{:keys [url url-env]} (providers n)
        u (or (not-empty (get env url-env)) url)]
    (str/replace (if (re-find #"^https?://" u) u (str "http://" u)) #"/+$" "")))

(defn- model [env n] (or (not-empty (get env "BBA_MODEL")) (:model (providers n))))

(defn model-name "The model the selected provider will use." [env] (model env (provider-name env)))

(defn anthropic
  "Return a provider fn for the Anthropic API, configured from `env`."
  [env]
  (fn [{:keys [on-text] :as req}]
    (let [http-req (build-request req {:api-key (get env "ANTHROPIC_API_KEY") :model (get env "BBA_MODEL")})]
      (if on-text
        (anthropic-message (post-stream http-req #(anthropic-step %1 %2 on-text) {:blocks (sorted-map)}))
        (post-json http-req)))))

(defn chat-completions
  "Return a provider fn for provider `n` (openai, openrouter or ollama), configured from `env`."
  [n env]
  (let [{:keys [key path]} (providers n)]
    (fn [{:keys [on-text] :as req}]
      (let [http-req (openai/build-request req {:url (str (base-url env n) path)
                                                :api-key (when key (get env key))
                                                :model (model env n)})]
        (openai/->reply (if on-text
                          (openai/stream-body (post-stream http-req #(openai/chunk-step %1 %2 on-text) openai/stream-init))
                          (post-json http-req)))))))

(defn account-id
  "The ChatGPT account id that goes with the Codex token, when the token names one."
  [token]
  (codex-auth/account-id token))

(defn codex
  "Return a provider fn for the Codex endpoint, authenticated by the ChatGPT
  subscription that the Codex CLI signed in with. The endpoint always streams, so
  unlike the other providers there is no non-streaming path."
  [env]
  (let [token (codex-auth/access-token env)]
    (fn [{:keys [on-text] :as req}]
      (let [http-req (responses/build-request req {:url (base-url env "codex")
                                                   :access-token token
                                                   :account-id (account-id token)
                                                   :model (model env "codex")})]
        (responses/reply-of-stream
         (post-stream http-req #(responses/chunk-step %1 %2 on-text) responses/stream-init))))))

(defn openai [env] (chat-completions "openai" env))
(defn ollama [env] (chat-completions "ollama" env))
(defn openrouter [env] (chat-completions "openrouter" env))

(defn from-env [env]
  (let [n (provider-name env)]
    (case n
      "anthropic" (anthropic env)
      "codex" (codex env)
      (chat-completions n env))))
