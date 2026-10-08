(ns bba.responses
  "Pure translation between bba's messages (Anthropic form) and the OpenAI
  Responses API, as served by the ChatGPT subscription endpoint
  (chatgpt.com/backend-api/codex/responses).

  This is not the Chat Completions API: see bba.openai for that. The differences
  that matter here:

  - the system prompt is a top-level `instructions` string, not a message;
  - the conversation is one flat `input` list of typed items, not role messages;
  - a tool result is its own `function_call_output` item that refers back to the
    `call_id` of the `function_call` item, not a \"tool\" role message;
  - the stream is a sequence of named events (`response.output_text.delta`, ...);
  - and streaming is not optional, the server rejects stream=false."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- blocks [content] (if (string? content) [{:type "text" :text content}] content))

(defn- text-of [bs] (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) bs)))

(defn- of-type [t bs] (filter #(= t (:type %)) bs))

(defn ->input
  "Anthropic-form messages -> Responses API `input` items.
  tool_use blocks become function_call items; tool_result blocks become
  function_call_output items."
  [messages]
  (into []
        (mapcat (fn [{:keys [role content]}]
                  (let [bs (blocks content) text (text-of bs)]
                    (if (= role "assistant")
                      (concat
                       (when-not (str/blank? text)
                         [{:type "message" :role "assistant"
                           :content [{:type "output_text" :text text}]}])
                       (for [{:keys [id name input]} (of-type "tool_use" bs)]
                         {:type "function_call" :call_id id :name name
                          :arguments (json/generate-string (or input {}))}))
                      (concat
                       (for [{:keys [tool_use_id content]} (of-type "tool_result" bs)]
                         {:type "function_call_output" :call_id tool_use_id
                          :output (str content)})
                       (when-not (str/blank? text)
                         [{:type "message" :role "user"
                           :content [{:type "input_text" :text text}]}]))))))
        messages))

(defn ->tools
  "Anthropic-form tool specs -> Responses API tools (flat, not nested under `function`)."
  [tools]
  (mapv (fn [{:keys [name description input_schema]}]
          (cond-> {:type "function" :name name :parameters input_schema}
            (some? description) (assoc :description description)))
        tools))

(def default-max-tokens
  "Cap on one reply. The Responses API treats this as a maximum, not a target, and
  reasoning tokens count against it. Matches the Anthropic provider's 8192."
  8192)

(defn build-request
  "Pure: the HTTP request for one Responses API call. `stream` is always true
  because the endpoint requires it. Returns {:uri :headers :body}."
  [{:keys [messages system tools]} {:keys [url access-token model max-tokens account-id]}]
  {:uri (str url "/responses")
   :headers (cond-> {"content-type" "application/json"}
              access-token (assoc "authorization" (str "Bearer " access-token))
              account-id (assoc "chatgpt-account-id" account-id))
   :body (cond-> {:model model :instructions system :input (->input messages)
                  :store false :stream true
                  :max_output_tokens (or max-tokens default-max-tokens)}
           (seq tools) (assoc :tools (->tools tools)))})

(defn- parse-arguments
  "The server sends arguments as a JSON string; tolerate an object and bad JSON."
  [a]
  (cond (map? a) a
        (str/blank? a) {}
        :else (try (json/parse-string a true) (catch Exception _ {}))))

(defn ->reply
  "A folded Responses stream -> a bba assistant message (Anthropic form).
  Throws when the server stopped early and left no usable answer, rather than
  returning an empty message that would look like a finished turn."
  [{:keys [text calls incomplete]}]
  (let [calls (vals calls)]
    (when (and (str/blank? text) (empty? calls))
      (throw (ex-info (if incomplete
                        (str "no answer from the model (" incomplete "): "
                             (if (= incomplete "max_output_tokens")
                               "it ran out of room while thinking, so try a shorter question or a lower reasoning effort"
                               "the server stopped before producing an answer"))
                        ;; No text and no tool call, yet the stream ended cleanly:
                        ;; usually a reasoning-only turn. Returning an empty
                        ;; assistant message here would look like a finished answer
                        ;; and, once logged, leave a `content: []` turn in the
                        ;; session. Fail the turn instead.
                        "no answer from the model: the server streamed an empty response (no text and no tool call)")
                      (cond-> {} incomplete (assoc :incomplete incomplete)))))
    {:role "assistant"
     :stop_reason (if (seq calls) "tool_use" "end_turn")
     :content (into (if (str/blank? text) [] [{:type "text" :text text}])
                    (map-indexed (fn [i {:keys [call_id name arguments]}]
                                   {:type "tool_use" :id (or (not-empty call_id) (str "call_" i))
                                    :name name :input (parse-arguments arguments)}))
                    calls)}))

;; ---------------------------------------------------------------- streaming

(def stream-init
  "Accumulator for `chunk-step`: streamed text, function calls by output index, and the
  reason the server stopped early when it did."
  {:text "" :calls (sorted-map) :incomplete nil})

(defn chunk-step
  "Pure except `on-text`: fold one Responses API stream event into acc.

  Tool calls are streamed in pieces: the item appears with its name first, the
  arguments then arrive as deltas, and the event carrying `arguments` completes
  the item. Any of those may be the only one that arrives, so each is merged."
  [acc event on-text]
  (let [t (:type event)]
    (cond
      (or (= t "error") (:error event))
      (throw (ex-info (str "API error: " (or (get-in event [:error :message]) (:message event)
                                               (:error event) t)) {}))

      (= t "response.failed")
      (throw (ex-info (str "API error: " (or (get-in event [:response :error :message])
                                               (get-in event [:response :error]) "response failed")) {}))

      (= t "response.output_text.delta")
      (do (when (and on-text (seq (:delta event))) (on-text (:delta event)))
          (update acc :text str (:delta event)))

      (= t "response.output_item.added")
      (let [{:keys [output_index item]} event]
        (if (= "function_call" (:type item))
          (assoc-in acc [:calls output_index]
                    {:call_id (:call_id item) :name (:name item) :arguments (:arguments item)})
          acc))

      (= t "response.function_call_arguments.delta")
      (update-in acc [:calls (:output_index event) :arguments] str (:delta event))

      (= t "response.function_call_arguments.done")
      (assoc-in acc [:calls (:output_index event) :arguments] (:arguments event))

      ;; The server stopped early, usually because the token cap was reached while
      ;; reasoning. Record it: the reply is empty or cut short, and the caller must
      ;; not present that as a finished answer.
      (= t "response.incomplete")
      (assoc acc :incomplete (get-in event [:response :incomplete_details :reason] "incomplete"))

      (= t "response.output_item.done")
      (let [{:keys [output_index item]} event]
        (if (= "function_call" (:type item))
          (update-in acc [:calls output_index]
                     #(merge (or % {}) {:call_id (:call_id item) :name (:name item)
                                        :arguments (:arguments item)}))
          acc))

      :else acc)))

(defn reply-of-stream
  "A folded stream -> a bba assistant message (Anthropic form)."
  [acc]
  (->reply acc))