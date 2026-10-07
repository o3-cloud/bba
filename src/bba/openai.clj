(ns bba.openai
  "Pure translation between bba's messages (Anthropic form) and the OpenAI
  Chat Completions API. OpenAI and Ollama (at /v1) both use this API."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- blocks [content] (if (string? content) [{:type "text" :text content}] content))

(defn- text-of [bs] (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) bs)))

(defn- of-type [t bs] (filter #(= t (:type %)) bs))

(defn ->messages
  "Anthropic-form system + messages -> Chat Completions messages.
  tool_result blocks become role \"tool\" messages; tool_use blocks become tool_calls."
  [system messages]
  (into (if (str/blank? system) [] [{:role "system" :content system}])
        (mapcat (fn [{:keys [role content]}]
                  (let [bs (blocks content) text (text-of bs)]
                    (if (= role "assistant")
                      [(cond-> {:role "assistant" :content (not-empty text)}
                         (seq (of-type "tool_use" bs))
                         (assoc :tool_calls (mapv (fn [{:keys [id name input]}]
                                                    {:id id :type "function"
                                                     :function {:name name :arguments (json/generate-string (or input {}))}})
                                                  (of-type "tool_use" bs))))]
                      (concat (for [{:keys [tool_use_id content]} (of-type "tool_result" bs)]
                                {:role "tool" :tool_call_id tool_use_id :content (str content)})
                              (when-not (str/blank? text) [{:role "user" :content text}]))))))
        messages))

(defn ->tools [tools]
  (mapv (fn [{:keys [name description input_schema]}]
          {:type "function" :function {:name name :description description :parameters input_schema}})
        tools))

(defn build-request
  "Pure: the HTTP request for one Chat Completions call."
  [{:keys [messages system tools]} {:keys [url api-key model]}]
  {:uri url
   :headers (cond-> {"content-type" "application/json"}
              api-key (assoc "authorization" (str "Bearer " api-key)))
   :body (cond-> {:model model :messages (->messages system messages)}
           (seq tools) (assoc :tools (->tools tools)))})

(defn- parse-arguments
  "OpenAI sends arguments as a JSON string; some servers send an object."
  [a]
  (cond (map? a) a
        (str/blank? a) {}
        :else (try (json/parse-string a true) (catch Exception _ {}))))

(defn ->reply
  "Chat Completions response body -> bba assistant message (Anthropic form)."
  [body]
  (when (and (:error body) (empty? (:choices body)))  ; OpenRouter can send errors with HTTP 200
    (throw (ex-info (str "API error: " (get-in body [:error :message] (:error body))) {})))
  (let [{:keys [content tool_calls]} (get-in body [:choices 0 :message])]
    {:role "assistant"
     :stop_reason (if (seq tool_calls) "tool_use" "end_turn")
     :content (into (if (str/blank? content) [] [{:type "text" :text content}])
                    (map-indexed (fn [i {:keys [id function]}]
                                   {:type "tool_use" :id (or (not-empty id) (str "call_" i))
                                    :name (:name function) :input (parse-arguments (:arguments function))}))
                    tool_calls)}))

;; ---------------------------------------------------------------- streaming

(def stream-init {:content "" :tool_calls (sorted-map)})

(defn chunk-step
  "Pure except `on-text`: fold one streamed chunk into acc. Tool calls arrive in parts, keyed by index."
  [acc chunk on-text]
  (when-let [e (:error chunk)]
    (throw (ex-info (str "API error: " (or (:message e) e)) {})))
  (let [{:keys [content tool_calls]} (get-in chunk [:choices 0 :delta])]
    (when (and on-text (seq content)) (on-text content))
    (reduce (fn [acc {:keys [index id function]}]
              (let [args (:arguments function)]
                (update-in acc [:tool_calls (or index 0)]
                           #(cond-> (or % {:type "function" :function {:name "" :arguments ""}})
                              (seq id) (assoc :id id)
                              (:name function) (update-in [:function :name] str (:name function))
                              args (update-in [:function :arguments] str (if (string? args) args (json/generate-string args)))))))
            (update acc :content str content)
            tool_calls)))

(defn stream-body
  "Folded stream -> a response body for `->reply`."
  [{:keys [content tool_calls]}]
  {:choices [{:message {:content content :tool_calls (vec (vals tool_calls))}}]})
