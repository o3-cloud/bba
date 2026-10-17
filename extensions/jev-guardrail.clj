(ns bba.extensions.jev-guardrail
  "Fail-closed pre-execution guardrail for BBA tool calls."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [bba.ext :as ext]
            [bba.provider :as provider]))

(def default-model "typesafe/jev-1.14")
(def default-threshold 69)

(defn enabled?
  "Return true when the Jev guardrail is enabled by the environment.
  The guardrail is enabled by default. Only the exact value false turns it off, so
  a missing or an unreadable value keeps the fail-closed behavior (BDR-001 AC-14)."
  [env]
  (not= "false" (str/lower-case (str (get env "BBA_GUARDRAIL_ENABLED" "true")))))

(defn threshold
  "Read the blocking threshold: the lowest score that blocks. Use 69 when the value
  is invalid."
  [env]
  (let [n (try (Integer/parseInt (get env "BBA_GUARDRAIL_THRESHOLD"))
               (catch Exception _ default-threshold))]
    (if (<= 0 n 100) n default-threshold)))

(defn- response-text
  "The text blocks of a provider reply, joined."
  [reply]
  (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) (:content reply))))

(defn parse-decision
  "Parse and validate Jev JSON. Return a decision map or throw on invalid data."
  [text]
  (let [decision (json/parse-string text true) score (:score decision)]
    (when-not (and (map? decision) (number? score) (<= 0 score 100))
      (throw (ex-info "Jev returned an invalid guardrail decision" {})))
    decision))

(defn prompt
  "Build the safety classification prompt."
  [name input]
  (str "Classify this BBA tool call for danger. Return JSON only.\n"
       "Use score 0 for safe and 100 for extremely dangerous.\n"
       "Required JSON fields: score, dangerous, reason.\n"
       "Tool name: " name "\nTool input: " (json/generate-string input)))

(defn classify
  "Call the System One API (Clef on ollama.com unless BBA_GUARDRAIL_PROVIDER says
  otherwise) and return a validated 0 to 100 decision."
  [{:keys [env]} name input]
  (let [call (or (ns-resolve 'bba.provider 'systemone)
                 (fn [e]
                   (let [body {:model (or (get e "BBA_GUARDRAIL_MODEL") "jev-1.14")
                               :state (:state e) :questions (:questions e)}
                         response (http/post "https://openrouter.ai/api/v1/systemone"
                                             {:headers {"Authorization" (str "Bearer " (get e "OPENROUTER_API_KEY"))
                                                        "Content-Type" "application/json"}
                                              :body (json/generate-string body)
                                              :throw false})]
                     (if (<= 200 (:status response) 299)
                       (json/parse-string (:body response) true)
                       (throw (ex-info (str "API error " (:status response)) {:status (:status response)}))))))
        factory (if (var? call) @call call)
        ;; provider/systemone picks the backend and its default model.
        call (factory env)
        reply (call {:state (prompt name input)
                     :questions {"score" {:type "noul"
                                          :instructions "How dangerous is this tool call? Return 0 for safe and 1 for extremely dangerous."}}})
        ;; post-json keywordizes the reply, so the answer is under :score.
        value (get-in reply [:answers :score :noul])
        reason (:reason reply)
        legacy (when (nil? value) (try (json/parse-string (response-text reply) true) (catch Exception _ nil)))
        score (if (number? value) (int (* 100 (double value))) (:score legacy))]
    (when-not (and (number? score) (<= 0 score 100))
      (throw (ex-info "Jev returned an invalid guardrail decision" {})))
    (cond-> {:score score :dangerous (>= score (threshold env))}
      (or reason (:reason legacy)) (assoc :reason (or reason (:reason legacy))))))

(defn- failure-detail
  "A short description of a classification failure. It never carries a secret: the
  provider reports the HTTP status in the exception data, and the exception message
  can echo the request, so the message does not appear here (BDR-001 AC-13)."
  [e]
  (if-let [status (:status (ex-data e))]
    (str "API error " status)
    (.getName (class e))))

(defn- guard-event
  "The event the display shows for a classification: the Jev fields, the exact prompt
  sent to Jev, and `blocked?`. `:text` is the one-line form for a session that has no
  terminal."
  [name input source decision blocked?]
  (let [reason (:reason decision) blocked (boolean blocked?)]
    (assoc decision
           :phase :guardrail :tool name :input input :source source :blocked blocked
           :text (str "Jev guardrail: " name " score=" (:score decision)
                      " dangerous=" (:dangerous decision) " blocked=" blocked
                      (when reason (str ": " reason))))))

(defn guard
  "Return a block result for a dangerous call, or nil to allow it."
  [{:keys [name input]} ctx]
  (when (enabled? (:env ctx))
    (let [source (prompt name input)
          show (:show ctx)]
      (try
        (let [decision (classify ctx name input)
              blocked? (:dangerous decision)]
          (when show (show (guard-event name input source decision blocked?)))
          (when blocked?
            {:block true
             :reason (str "Jev blocked tool " name " with score " (:score decision) ": "
                          (or (:reason decision) "dangerous action"))}))
        (catch Exception e
          (let [detail (failure-detail e)]
            (ext/warn "Jev guardrail failed closed: " detail)
            (when show
              (show (guard-event name input source
                                 {:score 100 :dangerous true
                                  :reason (str "classification failed: " detail)}
                                 true)))
            {:block true :reason "Jev guardrail could not classify the tool call"}))))))

(ext/on! :tool-call guard)
