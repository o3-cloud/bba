(ns bba.provider
  "The default provider: the Anthropic Messages API."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(def api-url "https://api.anthropic.com/v1/messages")
(def default-model "claude-opus-5-5")

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

(defn anthropic
  "Return a provider fn for the Anthropic API, configured from `env`."
  [env]
  (fn [req]
    (let [{:keys [uri headers body]} (build-request req {:api-key (get env "ANTHROPIC_API_KEY")
                                                         :model (get env "BBA_MODEL")})
          resp (http/post uri {:headers headers :body (json/generate-string body) :throw false})]
      (if (<= 200 (:status resp) 299)
        (try (json/parse-string (:body resp) true)
             (catch Exception _ (throw (api-error (:status resp) "response is not JSON"))))
        (throw (api-error (:status resp) (:body resp)))))))
