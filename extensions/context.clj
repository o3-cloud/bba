(ns bba.extensions.context
  "Built-in extension: report the context window and how full it is.

  Two surfaces share one measurement:
  - /context prints a per-category breakdown: the system prompt, the tool
    specs (with the skills catalog inside the activate_skill description), the
    message history, and the share of the window that they use.
  - A :turn-stop hook replaces the dim rule that closes a turn with a shorter
    form of the same numbers.

  The used count comes from the provider, not from a character estimate. Every
  provider reports the prompt tokens it saw in its reply, so the input side of
  the last reply of a turn is the true size of the context.

  The window comes from a static table, or from BBA_CONTEXT_WINDOW, which wins.
  A model that is not in the table reports an unknown window: the breakdown is
  still exact, and the share is a question mark.

  The breakdown splits what a category costs to send, never what it costs to
  keep. The on-disk session is shown apart from the sent size, because
  compaction sends a smaller view while the file keeps everything."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.math :as math]
            [clojure.string :as str]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.provider :as provider]
            [bba.toolview :as toolview]
            [bba.tui :as tui]
            [bba.ui :as ui]))

;; ------------------------------------------------------------- window (pure)

(def windows
  "model -> context window in tokens. The number is the usable prompt budget,
  not the largest window the vendor announced. A local model is absent on
  purpose: its window is a property of the installed copy, so it is probed."
  {"deepseek-v4.1-flash:cloud" 1048576
   "deepseek-v4.1" 131072
   "claude-opus-5-5" 200000
   "claude-opus-5.5" 200000
   "claude-sonnet-5-5" 200000
   "gpt-5" 400000
   "gpt-5-mini" 400000
   "gpt-5-nano" 400000
   "anthropic/claude-opus-5.5" 200000
   "gpt-6.1-sol" 500000
   "gpt-6-astra" 272000
   "gpt-6-sol" 272000
   "gpt-6-luna" 272000
   "gpt-5.6-sol" 272000
   "gpt-5.6-terra" 272000
   "gpt-5.6-luna" 272000})

(defn table-window [model] (get windows model))

(defn env-window
  "The window that BBA_CONTEXT_WINDOW sets, or nil when unset or unreadable."
  [env]
  (when-let [n (some-> (not-empty (get env "BBA_CONTEXT_WINDOW")) str/trim parse-long)]
    (when (pos? n) n)))

(defn window
  "The context window in tokens: the environment override, else the table, else nil."
  [env]
  (or (env-window env) (table-window (provider/model-name env))))

(defn window-source
  "Where the window came from: :env, :table, :probe or :unknown."
  [env probed]
  (cond (env-window env) :env (table-window (provider/model-name env)) :table probed :probe :else :unknown))

;; ------------------------------------------------------------- probe (ollama)

(defonce state (atom {:probed nil :last-request nil}))

(defn ollama-url
  "The base URL of the local Ollama server, from OLLAMA_HOST when it is set."
  [env]
  (let [u (or (not-empty (get env "OLLAMA_HOST")) "http://localhost:11434")]
    (str/replace (if (re-find #"^https?://" u) u (str "http://" u)) #"/+$" "")))

(defn context-length
  "The context window from an /api/show reply: the model_info key that ends in
  .context_length. The key carries the model family, so it is searched, not named."
  [info]
  (some (fn [[k v]]
          (when (and (str/ends-with? (name k) ".context_length") (number? v)) v))
        info))

(defn probe!
  "Ask the local Ollama server for the window of its model, once per session.
  Returns the window in tokens or nil. A server that is absent, slow or
  non-Ollama gives nil: the caller reports an unknown window."
  [ctx]
  (when (= "ollama" (provider/provider-name (:env ctx)))
    (try
      (let [resp (http/post (str (ollama-url (:env ctx)) "/api/show")
                            {:body (json/generate-string
                                    {:model (provider/model-name (:env ctx))})
                             :timeout 2000
                             :throw false})]
        (when (= 200 (:status resp))
          (context-length (:model_info (json/parse-string (:body resp) true)))))
      (catch Exception _ nil))))

(defn remember-probe!
  "Probe on the first call of a session and keep the answer. A nil answer is
  kept too, so a stopped server is not asked again on every turn."
  [ctx]
  (let [probed (:probed @state)]
    (if (contains? probed :value)
      (:value probed)
      (let [v (probe! ctx)] (swap! state assoc :probed {:value v}) v))))

;; ---------------------------------------------------------- parts (pure)

(defn text-chars
  "The characters of the text blocks of a message. A string content is text.
  A tool result is counted by the caller, because it is not prompt prose."
  [m]
  (let [c (:content m)]
    (if (string? c) (count c) (reduce + 0 (map #(count (str (:text %))) (filter #(= "text" (:type %)) c))))))

(defn json-chars [x] (count (json/generate-string x)))

(defn estimate
  "A token estimate for characters: four characters per token, the same ratio as
  compaction, so the two extensions never disagree about the size of a message."
  [chars] (quot (long chars) 4))

(defn messages-part
  "The message history, split by role, in characters and tokens."
  [msgs]
  (let [user (reduce + 0 (keep #(when (= "user" (:role %)) (text-chars %)) msgs))
        assistant (reduce + 0 (keep #(when (= "assistant" (:role %)) (text-chars %)) msgs))
        tool (reduce + 0 (keep #(when (seq (filter (fn [b] (= "tool_result" (:type b))) (:content %)))
                                  (json-chars (:content %)))
                               msgs))]
    {:count (count msgs) :chars (+ user assistant tool) :tokens (estimate (+ user assistant tool))
     :user (estimate user) :assistant (estimate assistant) :tool (estimate tool)}))

(defn tools-part
  "The tool specs, in characters and tokens, with each tool's own cost.
  activate_skill is named apart: it carries the skills catalog in its description.
  The other field is what every other tool costs together. A tool with an empty
  description is not sent at all, so it costs nothing."
  [specs]
  (let [sized (map (fn [s] {:name (:name s) :chars (json-chars s)}) specs)
        total (reduce + 0 (map :chars sized))
        skill (or (some #(when (= "activate_skill" (:name %)) (:chars %)) sized) 0)
        tokens (estimate total)
        ;; from the total, so the two named parts always add up to it
        skills (estimate skill)]
    {:count (count specs) :chars total :tokens tokens
     :skills skills :skills-chars skill
     :other-skill (- tokens skills)}))

(defn percent
  "`used` as a whole percent of `total`, 0 when `total` is nil or zero."
  [used total]
  (if (and total (pos? total)) (math/round (* 100.0 (/ (double used) total))) 0))

;; -------------------------------------------------------------- measure

(defn sent-tokens
  "The size of the prompt that the provider saw. The provider's own count wins;
  the character estimate is the fallback for a provider that reports no usage."
  [last-usage parts]
  (or (:input last-usage) (+ (:system parts) (:tools parts) (:messages parts))))

(defn measure
  "Every number that /context and the turn rule show. `facts` comes from the
  :turn-stop event; an empty map measures the prompt alone. Pure."
  [{:keys [request messages last-usage usage cost millis tools status]} env probed]
  (let [sent (or (:messages request) messages)
        sys (count (str (:system request)))
        tpart (tools-part (:tools request))
        mpart (messages-part sent)
        on-disk (messages-part messages)
        parts {:system (estimate sys) :tools (:tokens tpart) :messages (:tokens mpart)}
        used (sent-tokens last-usage parts)
        win (or (window env) probed)]
    {:window win :source (window-source env probed) :used used
     :percent (percent used win) :free (when win (- win used))
     :system (estimate sys) :system-chars sys
     :tools tpart :messages mpart :on-disk on-disk
     :sent-count (count sent) :on-disk-differs? (not= (:chars mpart) (:chars on-disk))
     :provider (provider/provider-name env) :model (provider/model-name env)
     :usage usage :last-usage last-usage :cost cost :millis millis
     :tool-calls tools :status status}))
;; --------------------------------------------------------------- render

(defn used-str
  "The share of the window as one phrase, for the first row."
  [{:keys [window used percent free]}]
  (if window
    (str (toolview/tokens-str used) " / " (toolview/tokens-str window) " used (" percent "%)"
         " · " (toolview/tokens-str (max 0 free)) " free")
    (str (toolview/tokens-str used) " used · window unknown (set BBA_CONTEXT_WINDOW)")))

(def ^:private source-notes {:env "BBA_CONTEXT_WINDOW" :table "static table" :probe "local probe"})

(defn source-note
  "Where the window came from, as a short phrase."
  [{:keys [source window]}]
  (if (and window (source-notes source)) (source-notes source) "unset"))

(defn row-str
  "One label and its text, the label padded so the column of text lines up."
  [label text]
  (str (ui/dim (format "%-9s" label)) text))

(defn tool-notes
  "The tool row: the count, the skills catalog and the cost of the other tools."
  [{:keys [count tokens skills other-skill skills-chars]}]
  (str (toolview/tokens-str tokens) "  " count (if (= 1 count) " tool" " tools")
       (when (pos? skills-chars)
         (str " · activate_skill " (toolview/tokens-str skills) " (skills catalog)"))
       (when (and (pos? tokens) (pos? skills-chars))
         (str " · other " (toolview/tokens-str other-skill)))))

(defn message-notes
  "A message row: the count, then the size of each role."
  [{:keys [count tokens user assistant tool]}]
  (str (toolview/tokens-str tokens) "  " count (if (= 1 count) " message" " messages")
       " · user " (toolview/tokens-str user)
       " · assistant " (toolview/tokens-str assistant)
       " · tool results " (toolview/tokens-str tool)))
(defn turn-row
  "The turn row: what the last call sent in, what the whole turn made, and the cost."
  [{:keys [usage last-usage cost millis tool-calls]}]
  (str (toolview/tokens-str (:input (or last-usage usage))) " in"
       (when last-usage " (last call)")
       " · " (toolview/tokens-str (:output usage)) " out"
       (when cost (str " · " (toolview/cost-str cost)))
       (when millis (str " · " (format "%.0fs" (/ millis 1000.0))))
       (when (pos? (or tool-calls 0))
         (str " · " tool-calls (if (= 1 tool-calls) " tool" " tools")))))

(defn detail-rows
  "The rows of /context. The on-disk row appears only when compaction sends
  something smaller than the file, and the turn row only after a turn."
  [m]
  (let [{:keys [provider model usage on-disk on-disk-differs?]} m]
    (cond-> [(row-str "context" (used-str m))
             (row-str "provider" (str provider " · " model
                                      " · window " (or (:window m) "?")
                                      " (" (source-note m) ")"))
             (row-str "prompt" (str (toolview/tokens-str (:system m))
                                    "  base and AGENTS.md, " (:system-chars m) " chars"))
             (row-str "tools" (tool-notes (:tools m)))
             (row-str "messages" (message-notes (:messages m)))]
      on-disk-differs? (conj (row-str "on disk" (message-notes on-disk)))
      usage (conj (row-str "turn" (turn-row m))))))

(defn report
  "The full /context text."
  [m]
  (str/join "\n" (detail-rows m)))
(defn rule-parts
  "The fields of the turn rule, in order. The context share comes last, so a
  narrow terminal cuts it before the token counts."
  [m]
  (let [{:keys [tool-calls usage cost millis window percent free]} m
        turn (cond-> []
               (pos? (or tool-calls 0)) (conj (str tool-calls (if (= 1 tool-calls) " tool" " tools")))
               usage (conj (str (toolview/tokens-str (:input usage)) " in")
                           (str (toolview/tokens-str (:output usage)) " out"))
               cost (conj (toolview/cost-str cost))
               millis (conj (format "%.0fs" (/ millis 1000.0))))
        ;; the window field closes the rule, but it cannot make one on its own:
        ;; a rule with no turn numbers says nothing that a turn did
        ctx (if (seq turn)
              (cond-> []
                window (conj (str "ctx " percent "%")
                             (str (toolview/tokens-str (max 0 free)) " left")))
              [])]
    (into turn ctx)))

(defn rule-str
  "The dim rule that closes a turn, to `width`. A rule with no field is empty."
  [m width]
  (let [parts (rule-parts m)]
    (if (empty? parts)
      ""
      (toolview/fit (ui/dim (str "── " (str/join " · " parts) " ")) width))))

;; --------------------------------------------------------- presentation

(defn remember!
  "Keep the last measurement, so /context reports it without a new turn."
  [m] (swap! state assoc :last m) m)

(defn prompt-view
  "The request that the next turn sends, or nil when the ctx cannot build one.
  /context uses it before the first turn, so the prompt cost is real, not zero."
  [ctx]
  (try (core/prompt-request ctx []) (catch Exception _ nil)))

(defn show!
  "Print the report: in the pager on a terminal, plain otherwise. A report before the
  first turn keeps its measurement, so the footer has a number at the next prompt too."
  [ctx]
  (let [m (or (:last @state)
              (remember! (measure {:request (prompt-view ctx)} (:env ctx) (remember-probe! ctx))))
        text (report m)]
    (if (and (:tty? ctx) (not (:print ctx)))
      (tui/page! text)
      (println text))))

(defn turn-stop
  "The :turn-stop hook: measure the turn, keep it, and return the rule that
  replaces the default one. A failed measurement keeps the default rule."
  [facts ctx]
  (try
    (let [m (remember! (measure facts (:env ctx) (remember-probe! ctx)))
          rule (rule-str m (or (:width facts) (get-in ctx [:md :width]) 80))]
      (when (seq rule) {:turn-rule rule}))
    (catch Exception e (ext/warn "context hook failed: " (ex-message e)) nil)))

(defn footer
  "The :prompt-footer hook: the share of the window that the last turn used and the
  room that is left, or nil before a turn measured the prompt. Order 20, so it comes
  after the target part."
  [_ _ctx]
  (let [{:keys [window percent free]} (:last @state)]
    (when (and window percent)
      {:order 20
       :text (str "ctx " percent "% · " (toolview/tokens-str (max 0 free)) " left")})))

;; -------------------------------------------------------------- register

(ext/register-command! "context" (fn [_args ctx] (show! ctx) nil))

(ext/on! :turn-stop turn-stop)

(ext/on! :prompt-footer footer)

(ext/on! :session-start
         (fn [_ ctx]
           (swap! state assoc :last nil :probed nil)
           (remember-probe! ctx)
           nil))
