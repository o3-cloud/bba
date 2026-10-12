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
  label is a link to the call's full detail."
  ([tool input width] (call-str tool input width nil))
  ([tool input width url]
   (let [label (or (labels tool) tool)
         head (str (ui/cyan "●") " " (link url (ui/bold label)) " ")
         room (max 20 (- width (count label) 3))]
     (if (labels tool)
       (str head (clip (str/trim (str (:code input))) room)
            (when-let [rs (seq (:remove input))] (ui/dim (str " remove " (str/join " " rs)))))
       (str head (ui/dim (clip (json/generate-string input) room)))))))

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
