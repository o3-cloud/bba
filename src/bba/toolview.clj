(ns bba.toolview
  "Pure: how a tool call and its result look in the transcript. The world tools
  (execute_form, develop_form) show their Clojure code as code and their EDN result
  as a status line; any other tool shows its name and clipped JSON input."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [bba.ui :as ui]))

(def ^:private labels {"execute_form" "execute" "develop_form" "develop"})

(defn- clip
  "`s` on one line, cut to `n` characters with an ellipsis."
  [s n]
  (let [s (str/replace (str s) #"\s*\n\s*" " ")]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- link
  "`label` as an OSC 8 hyperlink to `url` when colour is on and there is a url:
  terminals that know the sequence make it clickable, others drop it."
  [url label]
  (if (and url (ui/enabled?))
    (str "\u001b]8;;" url "\u001b\\" label "\u001b]8;;\u001b\\")
    label))

(defn call-str
  "A tool call, collapsed to one line: a bold label, then for a world tool its code
  with the line breaks folded to spaces, else the clipped JSON input. With `url` the
  label is a link to the call's full detail. The result is never wider than `width`."
  ([tool input width] (call-str tool input width nil))
  ([tool input width url]
   (let [label (or (labels tool) tool)
         head (str (ui/cyan "●") " " (link url (ui/bold label)) " ")
         room (- width (count head))]
     (cond
       (< room 1) (subs head 0 (max 0 width))   ; too narrow for the label alone
       (labels tool)
       (let [code (clip (str/trim (str (:code input))) room)
             rs (seq (:remove input))
             tail (when rs (str " remove " (str/join " " rs)))
             room2 (- room (count code))]
         (str head code (when (and tail (pos? room2)) (ui/dim (clip tail room2)))))
       :else (str head (ui/dim (clip (json/generate-string input) room)))))))

(defn- tokens-str
  "A token count in a few characters: 845, 12.3k, 1.2M."
  [n]
  (cond (< n 1000) (str n)
        (< n 999500) (str/replace (format "%.1fk" (/ n 1e3)) #"\.0k$" "k")
        :else (str/replace (format "%.1fM" (/ n 1e6)) #"\.0M$" "M")))

(defn- cost-str [usd]
  (cond (zero? usd) "$0"
        (< usd 0.01) (format "$%.4f" usd)
        :else (format "$%.2f" usd)))

(defn turn-rule
  "The dim rule that closes a turn: the tool count (when there were calls), the
  input and output tokens and the cost (when known), and the elapsed seconds, then
  a line to `width`."
  [{:keys [tools millis usage cost]} width]
  (let [parts (cond-> []
                (pos? tools) (conj (str tools (if (= 1 tools) " tool" " tools")))
                usage (conj (str (tokens-str (:input usage)) " in") (str (tokens-str (:output usage)) " out"))
                cost (conj (cost-str cost))
                :always (conj (format "%.0fs" (/ millis 1000.0))))
        label (str "── " (str/join " · " parts) " ")]
    (ui/dim (str label (apply str (repeat (max 0 (- width (count label))) "─"))))))

(defn- read-result [s]
  (try (let [v (edn/read-string {:default (fn [_ v] v)} s)] (when (map? v) v))
       (catch Exception _ nil)))

(defn- value-str
  "A printed value for one line: a string value is read back, so it shows its text
  rather than its escapes (\\n, \\\")."
  [v]
  (let [v (str v)]
    (if (str/starts-with? v "\"")
      (try (let [s (edn/read-string v)] (if (string? s) (str/trim s) v))
           (catch Exception _ v))
      v)))

(defn detail-md
  "The full call and its result as markdown: the code in a clojure fence (or the
  JSON input), then the whole result -- a world tool's value read back, its error,
  or the raw content."
  [tool input content]
  (let [r (when (labels tool) (read-result content))
        code (if (labels tool) (str (:code input)) (json/generate-string input {:pretty true}))
        body (cond (nil? r) content
                   (some? (:value r)) (value-str (:value r))
                   :else content)
        fence (fn [lang s] (str "```" lang "\n" (str/trimr (str s)) "\n```"))]
    (str "### " (or (labels tool) tool) "\n\n"
         (fence (if (labels tool) "clojure" "json") code)
         (when-let [rs (seq (:remove input))] (str "\n\nremove: " (str/join " " rs)))
         "\n\n**Result**" (when r (str " (" (name (:status r)) ")")) "\n\n"
         (fence "" body) "\n")))

(defn result-str
  "A tool result, indented under its call. A world tool's EDN result becomes ✓ with
  the value (or the names it defined) or ✗ with the error class and message."
  [tool content error? width]
  (let [pre "  ⎿ "
        room (max 20 (- width 6))
        r (when (labels tool) (read-result content))
        size (ui/dim (str "  " (count (str (or (:value r) content))) " chars"))]
    (cond
      (and r (= :error (:status r)))
      (str pre (ui/red (str "✗ " (clip (str (last (str/split (str (:class r)) #"\.")) ": " (:message r)) room))))

      (and r (= :rejected (:status r)))
      (str pre (ui/yellow (str "✗ " (clip (str "rejected" (when-let [why (:reason r)] (str " (" (name why) ")"))
                                               (when (seq (:vars r)) (str ": " (str/join " " (:vars r)))))
                                          room))))

      r
      (let [names (concat (:defined r) (map #(str "-" %) (:removed r)))
            what (cond (seq names) (str "defined " (str/join " " names))
                       (some? (:value r)) (str "=> " (value-str (:value r)))
                       :else (name (:status r)))
            tag (when (= :preview (:status r)) (ui/dim " (preview)"))]
        (str pre (ui/green "✓ ") (clip what (- room 16)) tag size))

      error? (str pre (ui/red (str "✗ " (clip content room))))
      :else (str pre (ui/dim (str (count content) " chars"))))))

(def ^:private guardrail-source-lines
  "The most lines of the Jev prompt the transcript shows, so one huge tool input
  cannot flood the display."
  12)

(defn- guardrail-head
  "The one-line verdict of a Jev classification, clipped to `width`."
  [{:keys [tool score dangerous blocked]} width]
  (clip (str (ui/cyan "Jev guardrail") " " tool " score=" score
             " dangerous=" dangerous " blocked=" blocked)
        width))

(defn guardrail-str
  "What the transcript shows for one Jev classification. An allowed call is one line:
  the verdict and the Jev reason. A blocked call adds the reason on its own line and
  the exact prompt sent to Jev, with every line clipped to `width` and at most 12
  lines shown, so a huge tool input cannot flood the transcript."
  [{:keys [reason source] :as ev} width]
  (let [lines (str/split-lines (str source))
        shown (map #(clip % width) (take guardrail-source-lines lines))
        more (- (count lines) guardrail-source-lines)]
    (if-not (:blocked ev)
      (clip (str (guardrail-head ev width) (when reason (str ": " reason))) width)
      (str (guardrail-head ev width)
           "\n" (clip (str (or reason "no reason")) width)
           "\n" (ui/dim "Source sent to Jev:")
           (when (seq shown) (str "\n" (str/join "\n" shown)))
           (when (pos? more) (str "\n" (ui/dim (str "… " more " more lines"))))))))
