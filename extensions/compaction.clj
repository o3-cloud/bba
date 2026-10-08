(ns bba.extensions.compaction
  "Built-in extension: keep long sessions inside the context window.

  History is never destroyed. This wraps the provider and sends a smaller,
  derived view: the task statement, a summary of the middle, and the last few
  turns verbatim. The session file on disk is untouched, so a bad summary is
  always recoverable. That is the same doctrine as world revisions.

  Two modes:
  - :continuous (default) folds the older conversation into one rolling summary,
    a little at a time. Every message is summarized once, so the cost per turn is
    the newly matured text, not the whole history again.
  - :threshold summarizes the whole middle again each time the window fills. It
    gets more expensive as the session grows; it is kept for comparison.

  Config, project wins over user:
    .bba/compaction.edn          ~/.bba/compaction.edn
    {:enabled? true :mode :continuous :auto? true :threshold-tokens 60000
     :keep-turns 6 :fold-min-tokens 3000 :cache? true
     :summarizer {:provider nil :model nil :max-tokens 2048 :timeout-s 60}}

  A nil :provider or :model reuses the main session's provider and model, which
  is the only setting that can never fail for lack of setup.

  This registers a :request hook, so it sees the live ctx on every turn. It
  therefore composes with /provider: the summary uses the provider that was
  current when it ran.

  Commands: /compact [status|on|off|model PROVIDER [MODEL]]

  Invariant
  - A message is never dropped unless the rolling summary already covers it. If
    the summarizer fails, or the message is still too large after the whole tail
    is folded, the full history is sent: a failed fold costs tokens, not content.

  Limits
  - The rolling summary lives in memory, so it is rebuilt (one fold per stage)
    in each process. The cache makes that cheap; it does not remove the work.
  - The cache is a sidecar file, never a line in the session JSONL: core's
    load-session would parse a marker line as a message.
  - Fail-open: if the summarizer errors, the full history is sent instead."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [bba.ext :as ext]
            [bba.provider :as provider]))

(def defaults
  {:enabled? true
   :mode :continuous          ; :continuous folds forward; :threshold re-summarizes the middle
   :auto? true
   :threshold-tokens 60000    ; the ceiling for the protected tail, and the trigger in :threshold mode
   :keep-turns 6
   :fold-min-tokens 3000      ; :continuous only: do not call the summarizer for less than this
   :max-summary-tokens 2000   ; :continuous only: the rolling summary stays about this size
   :cache? true
   :summarizer {:provider nil :model nil :max-tokens 2048 :timeout-s 60}})

(defonce state (atom {:cfg nil :calls 0 :last nil :installed? false}))

;; ------------------------------------------------------------------ config

(defn- read-edn [path]
  (when (fs/exists? path)
    (try (edn/read-string (slurp (str path)))
         (catch Exception e (ext/warn "ignoring bad config " path ": " (ex-message e)) nil))))

(defn config
  "User config merged with project config, over the defaults."
  [ctx]
  (merge defaults
         (read-edn (fs/path (:home ctx) "compaction.edn"))
         (read-edn (fs/path (:cwd ctx) ".bba" "compaction.edn"))))

;; ------------------------------------------------------------ messages (pure)

(defn blocks [m]
  (let [c (:content m)] (if (string? c) [{:type "text" :text c}] (vec c))))

(defn- has-block? [m t] (boolean (some #(= t (:type %)) (blocks m))))

(defn user-text? [m] (and (= "user" (:role m)) (not (has-block? m "tool_result"))))

(defn final-answer? [m] (and (= "assistant" (:role m)) (not (has-block? m "tool_use"))))

(defn msg-chars [m] (count (json/generate-string m)))

(defn estimate-tokens
  "Rough token count: about 4 characters per token."
  [msgs] (quot (reduce + 0 (map msg-chars msgs)) 4))

(defn exchanges
  "Complete exchanges: a user text message plus everything up to the assistant
  answer that ends it. Mirrors core/replayable, so a group never splits a
  tool_use from its tool_result. A trailing turn with no answer yet is its own
  group, so an in-flight prompt is never dropped."
  [msgs]
  (loop [[m & more] msgs groups [] cur nil]
    (cond
      (nil? m) (cond-> groups (seq cur) (conj (vec cur)))
      (user-text? m) (recur more (cond-> groups (seq cur) (conj (vec cur))) [m])
      (nil? cur) (recur more groups nil)
      (final-answer? m) (recur more (conj groups (vec (conj cur m))) nil)
      :else (recur more groups (conj cur m)))))

(defn plan
  "What to drop. Protects the first user message and the last `keep-turns`
  exchanges, drops whole exchanges only, and trims the tail from its oldest end
  when the protected tail alone already exceeds the target. :middle is a flat
  vector of messages, never nested."
  ([msgs] (plan msgs {}))
  ([msgs {:keys [keep-turns target-tokens] :or {keep-turns 6 target-tokens 60000}}]
   (let [xs (exchanges msgs) n (count xs)]
     (if (< n 2)
       {:needed? false :exchanges n :reason :nothing-to-drop}
       (let [head-msg (first (first xs))
             cap (min keep-turns (dec n))
             tail-tokens (fn [k] (estimate-tokens (apply concat (take-last k xs))))
             fits? (fn [k] (<= (+ (estimate-tokens [head-msg]) (tail-tokens k)) target-tokens))
             keep (or (some (fn [k] (when (fits? k) k)) (range cap 0 -1)) 1)
             tail (vec (take-last keep xs))
             mid-exchanges (when (> (- n keep) 1) (subvec xs 1 (- n keep)))
             middle (vec (concat (rest (first xs)) (mapcat identity mid-exchanges)))]
         (if (empty? middle)
           {:needed? false :exchanges n :reason :tail-is-everything :keep keep}
           {:needed? true :exchanges n :keep keep :keep-max keep-turns
            :trimmed? (< keep (min keep-turns (dec n)))
            :middle-count (count middle)
            :before-tokens (estimate-tokens msgs)
            :after-tokens (estimate-tokens (into [head-msg] (apply concat tail)))
            :dropped-tokens (estimate-tokens middle)
            :head-msg head-msg :middle middle :tail tail}))))))

(defn summary-text [n summary]
  (str "## Summary of earlier conversation (" n " messages compacted)\n\n" summary))

(defn compacted
  "The derived view: task statement, summary appended to it, then the tail."
  [p summary]
  (let [head (update (:head-msg p) :content
                     (fn [c] (conj (vec c) {:type "text" :text (summary-text (:middle-count p) summary)})))]
    (into [head] (apply concat (:tail p)))))

;; ---------------------------------------------------------------- summarizer

(defn summarizer-system []
  (str "You compress a coding agent's conversation so it can continue with less context.\n"
       "You get a transcript excerpt. Return only the summary, no preamble.\n"
       "Use exactly these sections, and keep every fact that will matter later:\n"
       "## Task\n(what the user asked for)\n"
       "## Decisions\n(what was chosen and why)\n"
       "## Files touched\n(paths, and what changed)\n"
       "## Code written\n(function names, and anything now defined)\n"
       "## Failures and dead ends\n(so they are not retried)\n"
       "## Open threads\n(unfinished work and the next step)\n"
       "Prefer concrete names, paths, commands and values over prose. Never invent."))

(defn- clip-str [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "\n...[cut: " (count s) " chars]") s)))

(defn- brief [s] (clip-str s 1500))

;; A fold may carry a long stretch of transcript. The cap is generous (about
;; 30k tokens) so a real stretch is rarely cut; it only stops a pathological one
;; from becoming a huge summarizer call. The summary it returns stays small.
(def fold-max-chars 120000)

(defn- brief-json [v]
  (let [s (json/generate-string (or v {}))] (if (> (count s) 300) (str (subs s 0 300) "...") s)))

(defn render-middle
  "Transcript for the summarizer. Each message is capped, so the summarizer call
  is never larger than the range it replaces."
  [middle]
  (let [render (fn [m]
                 (->> (blocks m)
                      (map (fn [b]
                             (case (:type b)
                               "text" (str (:text b))
                               "tool_use" (str "[tool_use " (:name b) " " (brief-json (:input b)) "]")
                               "tool_result" (str "[tool_result " (brief (:content b)) "]")
                               "")))
                      (str/join "\n")))]
    (str "Transcript excerpt to compress:\n\n"
         (->> middle
              (map (fn [m] (str (or (:role m) "?") ": " (brief (render m)))))
              (str/join "\n\n")))))

(defn summarizer-provider
  "Build the summarizer's provider. nil :provider / :model reuses the session's."
  [cfg ctx]
  (let [{:keys [provider model]} (:summarizer cfg)]
    (provider/from-env (cond-> (:env ctx)
                         provider (assoc "BBA_PROVIDER" provider)
                         model (assoc "BBA_MODEL" model)))))

(defn call-summarizer-text
  "Call the summarizer with a system prompt and one user text. Returns its text."
  [cfg ctx system text]
  (let [{:keys [timeout-s]} (:summarizer cfg)
        fut (future ((summarizer-provider cfg ctx)
                     {:messages [{:role "user" :content [{:type "text" :text text}]}]
                      :system system}))]
    (let [r (deref fut (* 1000 (or timeout-s 60)) ::timeout)]
      (if (= ::timeout r)
        (do (future-cancel fut)
            (throw (ex-info (str "summarizer timed out after " timeout-s " s") {})))
        (->> (blocks r)
             (keep #(when (= "text" (:type %)) (:text %)))
             (str/join "\n"))))))

(defn- call-summarizer [cfg ctx middle]
  (call-summarizer-text cfg ctx (summarizer-system) (render-middle middle)))

;; -------------------------------------------------------------------- cache

(defn- cache-file [ctx] (fs/path (:cwd ctx) ".bba" "sessions" ".compaction-cache.jsonl"))

(defn cache-key [model middle]
  (str model "|" (count middle) "|" (hash (json/generate-string middle))))

(defn- cache-get [ctx k]
  (let [f (cache-file ctx)]
    (when (fs/exists? f)
      (some (fn [l] (when (= (:key l) k) (:summary l)))
            (keep #(try (json/parse-string % true) (catch Exception _ nil))
                  (remove str/blank? (str/split-lines (slurp (str f)))))))))

(defn- cache-put! [ctx k summary]
  (let [f (cache-file ctx)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str (json/generate-string {:key k :summary summary}) "\n") :append true)))

(defn summarize
  "Summary for the dropped middle, from cache or newly requested. Never returns
  nil for an empty summary; the caller treats blank as a failure."
  [cfg ctx middle]
  (let [k (cache-key (or (get-in cfg [:summarizer :model]) "default") middle)]
    (or (when (:cache? cfg) (cache-get ctx k))
        (let [s (call-summarizer cfg ctx middle)]
          (when (and (:cache? cfg) (seq (str s))) (cache-put! ctx k s))
          s))))

;; ------------------------------------------------ continuous (rolling) mode
;;
;; A rolling summary is folded forward one new stretch at a time. Every message
;; is summarized once, so the cost per turn is the newly matured text, not the
;; whole history again. :threshold mode instead re-summarizes the whole middle
;; each time the window fills, which is why it gets more expensive as it goes.

(defn fold-system []
  (str "You maintain a rolling summary of a coding agent's conversation.
"
       "You get the current summary and the next piece of transcript.
"
       "Return the updated summary only, no preamble. Merge the new facts in.
"
       "Use these sections, and omit a section that would be empty:
"
       "## Task
## Decisions
## Files touched
## Code written
"
       "## Failures and dead ends
## Open threads
"
       "Drop detail that is no longer useful. Keep names, paths, commands and values.
"
       "Never invent. If the new transcript adds nothing, return the summary unchanged."))

(defn fold-key [model prev body]
  (str model "|fold|" (count (str prev)) "|" (hash (str body))))

(defn fold-content [prev body]
  (str (if (str/blank? (str prev))
         "Current summary: (none yet)"
         (str "Current summary:
" (brief (str prev))))
       "

New transcript to merge:
" (clip-str body fold-max-chars)))

(defn fold-summary
  "Fold `new-middle` into `prev`. One summarizer call; cached by content."
  [cfg ctx prev new-middle]
  (let [body (render-middle new-middle)
        k (fold-key (or (get-in cfg [:summarizer :model]) "default") prev body)]
    (or (when (:cache? cfg) (cache-get ctx k))
        (let [s (call-summarizer-text cfg ctx (fold-system) (fold-content prev body))]
          (when (and (:cache? cfg) (seq (str s))) (cache-put! ctx k s))
          s))))

(defn fold-forward
  "Advance the rolling summary over the messages that are now eligible to fold.
  Fails soft: on error the previous summary and fold point are kept, so the next
  turn retries the same stretch."
  [cfg ctx st mid-msgs]
  (let [st (if (> (:folded st) (count mid-msgs)) (assoc st :folded 0 :summary nil) st)
        new-msgs (vec (drop (:folded st) mid-msgs))]
    (if (< (estimate-tokens new-msgs) (:fold-min-tokens cfg))
      st
      (try
        (let [s (fold-summary cfg ctx (:summary st) new-msgs)]
          (if (seq (str s))
            (do (swap! state update :folds (fnil inc 0))
                (assoc st :folded (count mid-msgs) :summary s))
            (do (ext/warn "compaction: empty fold result; keeping the previous summary") st)))
        (catch Exception e
          (ext/warn "compaction fold failed: " (ex-message e))
          st)))))

(defn continuous-view
  "The request for :continuous mode: the task statement, a rolling summary of the
  older conversation, then the newest exchanges verbatim. Folding and trimming
  both move on whole exchanges, so a tool_use is never split from its
  tool_result. If the summarizer fails, or the message is still too large after
  the whole tail is folded, the full history is sent: a failed fold costs
  tokens, never content."
  [cfg ctx req]
  (let [msgs (:messages req) xs (exchanges msgs) n (count xs)
        total (estimate-tokens msgs)]
    (if (or (< n 2) (<= total (:threshold-tokens cfg)))
      req                                  ; below the ceiling: nothing to do
      (let [sid (or (:session-file ctx) "nosession")
            head-msg (first (first xs))
            max-keep (max 1 (min (:keep-turns cfg) (dec n)))
            keep0 (min max-keep (dec n))]
        (try
          ;; Keep as many trailing exchanges as fit. Folding more is always
          ;; safe (the summary keeps the content), so shrinking the tail is the
          ;; one lever that reduces size without losing anything.
          (loop [keep keep0]
            (let [eligible (- n keep)          ; exchanges folded into the summary
                  rest-first (rest (first xs))
                  mid-exchanges (when (> eligible 1) (subvec xs 1 eligible))
                  ;; `mid-msgs` is exactly the messages the summary covers: the
                  ;; rest of the first exchange (its head is kept separately),
                  ;; then every whole exchange before the tail.
                  mid-msgs (vec (concat rest-first (mapcat identity mid-exchanges)))
                  st (get-in @state [:rolling sid] {:folded 0 :summary nil})
                  st' (fold-forward cfg ctx st mid-msgs)
                  _ (swap! state assoc-in [:rolling sid] st')
                  summary (str (:summary st'))
                  folded (min (:folded st') (count mid-msgs))
                  tail (vec (subvec xs eligible))
                  view (vec (concat
                             [(if (seq summary)
                                (update head-msg :content
                                        (fn [c] (conj (vec c)
                                                      {:type "text"
                                                       :text (str "## Summary of earlier conversation\n\n"
                                                                  "(" folded " older messages, folded forward; "
                                                                  "the summary is rewritten in place)\n\n" summary)})))
                                head-msg)]
                             (subvec mid-msgs folded)
                             (apply concat tail)))]
              (cond
                (empty? summary) req           ; fold failed: send everything
                (and (> keep 1) (> (estimate-tokens view) (:threshold-tokens cfg)))
                (recur (dec keep))              ; still too big: fold more
                :else
                (do (swap! state assoc :last {:at (str (java.time.Instant/now))
                                              :before-tokens (estimate-tokens msgs)
                                              :after-tokens (estimate-tokens view)
                                              :folded folded
                                              :summary-chars (count summary)})
                    (swap! state update :calls inc)
                    (assoc req :messages view)))))
          (catch Exception e
            (ext/warn "compaction failed, sending full history: " (ex-message e))
            req))))))

;; ------------------------------------------------------------------ install

(defn compact-request
  "Pure given a summarizer. Apply compaction to one provider request and return
  the request to send. Any failure returns the request unchanged (fail-open)."
  [cfg ctx req]
  (if-not (and (:enabled? cfg) (:auto? cfg))
    req
    (if (= :continuous (:mode cfg))
      (continuous-view cfg ctx req)
      (let [msgs (:messages req)
            p (plan msgs {:keep-turns (:keep-turns cfg) :target-tokens (:threshold-tokens cfg)})]
      (if-not (and (:needed? p) (> (:before-tokens p) (:threshold-tokens cfg)))
        req
        (try
          (let [summary (summarize cfg ctx (:middle p))]
            (if (str/blank? (str summary))
              (do (ext/warn "compaction: empty summary; sending full history") req)
              (let [out (assoc req :messages (compacted p summary))]
                (swap! state assoc :last {:at (str (java.time.Instant/now))
                                          :before-tokens (:before-tokens p)
                                          :after-tokens (estimate-tokens (:messages out))
                                          :dropped (:middle-count p)
                                          :keep (:keep p)})
                (swap! state update :calls inc)
                out)))
          (catch Exception e
            (ext/warn "compaction failed, sending full history: " (ex-message e))
            req)))))))

(defn install!
  "Load the config and mark the hook ready. Called at :session-start. Runs again
  when the config changes, so a new config or summarizer model takes effect."
  [ctx]
  (swap! state assoc :cfg (config ctx) :installed? true))

(defn status-text []
  (let [{:keys [cfg calls last installed?]} @state]
    (str "compaction: " (if (and cfg (:enabled? cfg)) "on" "off")
         (when-not installed? " (not installed yet)")
         "\n  mode: " (name (or (:mode cfg) :continuous)) "   auto: " (get cfg :auto?)
         "   threshold: " (get cfg :threshold-tokens) " tokens"
         "   keep-turns: " (get cfg :keep-turns)
         "   cache: " (get cfg :cache?)
         "   folds: " (or (:folds @state) 0)
         "\n  summarizer: " (or (get-in cfg [:summarizer :provider]) "(session provider)")
         " / " (or (get-in cfg [:summarizer :model]) "(session model)")
         "\n  compactions this session: " calls
         (when last (str "\n  last: " (:before-tokens last) " -> " (:after-tokens last)
                         " tokens, dropped " (:dropped last) " messages, kept " (:keep last)
                         " turns")))))

(defn handle-request
  "A :request hook. Reads the live ctx, so it sees the current provider and env."
  [{:keys [request]} ctx]
  (let [cfg (or (:cfg @state) (config ctx))]
    {:request (compact-request cfg ctx request)}))

(ext/on! :session-start
        (fn [_ ctx] (try (install! ctx) (catch Exception e (ext/warn "compaction install failed: " (ex-message e))))))

(ext/on! :request handle-request)

(defn- set-cfg! [f & args] (swap! state update :cfg (fn [c] (apply f (or c defaults) args))))

(ext/register-command! "compact"
  (fn [args ctx]
    (let [[sub & more] (str/split (str/trim (or args "")) #"\s+")]
      (case (or sub "status")
        "status" (println (status-text))
        "on" (do (set-cfg! assoc :enabled? true) (println (status-text)))
        "off" (do (set-cfg! assoc :enabled? false) (println (status-text)))
        "model" (if (seq more)
                   (do (set-cfg! assoc :summarizer
                                 (assoc (get-in @state [:cfg :summarizer] (:summarizer defaults))
                                        :provider (first more) :model (second more)))
                       (println (status-text)))
                   (println (str "usage: /compact model PROVIDER [MODEL]  (currently "
                                 (or (get-in @state [:cfg :summarizer :provider]) "(session provider)") ")")))
        (println "usage: /compact [status|on|off|model PROVIDER [MODEL]]")))))