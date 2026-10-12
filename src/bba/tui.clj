(ns bba.tui
  "The interactive line editor, built on charm.clj. Key presses arrive as charm
  messages; charm message -> edit state is pure (`press->key`, `handle-key`), and so
  is the rendered frame (`view-str`). charm owns raw mode, wrapping and redrawing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [charm.components.viewport :as vp]
            [charm.message :as msg]
            [charm.program :as program]
            [charm.style.core :as style]))

;; The continuation prefix is derived from the prompt in `view-str`, so no `cont` def.

;; ---------------------------------------------------------------- terminal

(defn- sh-in [& args] (apply p/sh {:in :inherit} args))

(defn tty?
  "True when stdin and stdout are a terminal and TERM is not dumb."
  []
  (and (not= "dumb" (System/getenv "TERM"))
       (zero? (:exit (sh-in "test" "-t" "0")))
       (zero? (:exit (p/sh {:in :inherit :out :inherit} "test" "-t" "1")))))

(defn- stty [& args] (str/trim (:out (apply sh-in "stty" args))))

(defn width []
  (let [w (some-> (stty "size") (str/split #" ") second parse-long)]
    (if (and w (pos? w)) w 80)))  ; a pty with no size set reports 0

(defn height []
  (let [h (some-> (stty "size") (str/split #" ") first parse-long)]
    (if (and h (pos? h)) h 24)))

;; ---------------------------------------------------------------- history

(defn load-history [file]
  (if (and file (fs/exists? file))
    (->> (str/split-lines (slurp file))
         (keep #(try (json/parse-string %) (catch Exception _ nil)))
         (take-last 500) vec)
    []))

(defn add-history [hist file text]
  (if (or (str/blank? text) (= text (peek hist)))
    hist
    (do (when file
          (some-> (fs/parent file) fs/create-dirs)
          (spit (str file) (str (json/generate-string text) "\n") :append true))
        (conj hist text))))

;; ---------------------------------------------------------------- edit state

(defn new-state [hist] {:buf "" :pos 0 :hist hist :hidx (count hist) :draft ""})

(defn- insert [{:keys [buf pos] :as st} s]
  (assoc st :buf (str (subs buf 0 pos) s (subs buf pos)) :pos (+ pos (count s))))

(defn- delete-range [{:keys [buf] :as st} from to]
  (assoc st :buf (str (subs buf 0 from) (subs buf to)) :pos from))

(defn- word-start [buf pos]
  (let [before (subs buf 0 pos)
        trimmed (str/trimr before)]
    (inc (max (or (str/last-index-of trimmed " ") -1) (or (str/last-index-of trimmed "\n") -1)))))

(defn- history-move [{:keys [hist hidx buf draft] :as st} delta]
  (let [i (+ hidx delta)]
    (if (<= 0 i (count hist))
      (let [text (if (= i (count hist)) draft (hist i))]
        (assoc st :hidx i :buf text :pos (count text)
               :draft (if (= hidx (count hist)) buf draft)))
      st)))

(defn handle-key
  "Pure: apply one key to the edit state. A finished read sets :done to
  [:submit text], [:eof] or [:cancel]."
  [{:keys [buf pos] :as st} key]
  (let [[k arg] (if (vector? key) key [key])]
    (case k
      :insert     (insert st arg)
      :newline    (insert st "\n")
      :enter      (if (str/ends-with? (subs buf 0 pos) "\\")
                    (insert (delete-range st (dec pos) pos) "\n")  ; trailing \ continues the line
                    (assoc st :done [:submit buf]))
      :backspace  (if (pos? pos) (delete-range st (dec pos) pos) st)
      :delete     (if (< pos (count buf)) (delete-range st pos (inc pos)) st)
      :left       (assoc st :pos (max 0 (dec pos)))
      :right      (assoc st :pos (min (count buf) (inc pos)))
      :home       (assoc st :pos (inc (or (str/last-index-of (subs buf 0 pos) "\n") -1)))
      :end        (assoc st :pos (or (str/index-of buf "\n" pos) (count buf)))
      :kill-start (delete-range st (inc (or (str/last-index-of (subs buf 0 pos) "\n") -1)) pos)
      :kill-end   (delete-range st pos (or (str/index-of buf "\n" pos) (count buf)))
      :kill-word  (delete-range st (word-start buf pos) pos)
      :up         (history-move st -1)
      :down       (history-move st 1)
      :expand     (if (str/blank? buf) (assoc st :done [:expand]) st)  ; only at an empty prompt
      :ctrl-c     (if (str/blank? buf) (assoc st :done [:cancel]) (assoc st :buf "" :pos 0))
      :ctrl-d     (cond (= "" buf) (assoc st :done [:eof])
                        (< pos (count buf)) (delete-range st pos (inc pos))
                        :else st)
      st)))
;; Layout is charm's job now: `view-str` renders one frame, and charm wraps, diffs and
;; redraws it. The old hand-rolled `display`/`cursor-at` column arithmetic is gone.
;; ---------------------------------------------------------------- charm bridge

(def ^:private named-keys
  {:enter :enter :backspace :backspace :delete :delete :left :left :right :right
   :up :up :down :down :home :home :end :end :tab :tab :space [:insert " "]
   :page-up :up :page-down :down :escape nil})

(defn press->key
  "Pure: one charm message -> this editor's key vocabulary, or nil for a message the
  editor ignores (window resizes, focus changes, keys it does not bind)."
  [m]
  (when (msg/key-press? m)
    (let [k (:key m)
          c (when (and (string? k) (:ctrl m)) (str/lower-case k))]
      (cond
        (and c (= c "c")) :ctrl-c
        (and c (= c "d")) :ctrl-d
        (and c (= c "w")) :kill-word
        (and c (= c "u")) :kill-start
        (and c (= c "k")) :kill-end
        (and c (= c "a")) :home
        (and c (= c "e")) :end
        (and c (= c "b")) :left
        (and c (= c "f")) :right
        (and c (= c "p")) :up
        (and c (= c "n")) :down
        (and c (= c "o")) :expand
        ;; a real Alt+Enter is ESC CR, which charm reports as the rune "\r" with :alt
        (and (:alt m) (contains? #{:enter "\r" "\n"} k)) :newline
        (keyword? k) (get named-keys k)
        (string? k) [:insert k]
        :else nil))))

(def ^:private caret-style (style/style :reverse true))

(defn view-str
  "Pure: the charm view for one frame -- the shown prompt, the buffer with a blank
  continuation prefix on every newline, and a reverse-video caret at the cursor (a
  space when the cursor is past the end). `plain` is the prompt without styling: its
  width, not the shown prompt's, decides the continuation indent, so ANSI escapes in
  the styled prompt cannot shift the layout."
  [plain shown {:keys [buf pos]}]
  (let [buf (or buf "")
        pos (or pos 0)
        cur (if (< pos (count buf)) (subs buf pos (inc pos)) " ")
        marked (str (subs buf 0 pos) (style/render caret-style cur)
                    (subs buf (min (inc pos) (count buf))))
        blank (apply str (repeat (count plain) " "))]
    (str shown (str/replace marked "\n" (str "\n" blank)))))

;; ---------------------------------------------------------------- read loop

(defn- entry-update
  "One charm message -> [state cmd]. Sets :done on the state when the entry finishes."
  [st m]
  (cond
    (msg/paste? m) [(handle-key st [:insert (str/replace (str (:text m)) "\r\n" "\n")]) nil]
    :else
    (if-let [k (press->key m)]
      (let [st' (handle-key st k)]
        (if (:done st')
          ;; park the caret at the end for the final frame, then stop the loop
          [(assoc st' :pos (count (str (:buf st')))) program/quit-cmd]
          [st' nil]))
      [st nil])))

(defn read-line!
  "Read one entry with editing. Returns the text, nil on EOF, or :cancel on Ctrl-C at
  an empty prompt. `hist` is an atom holding the history vector; entries are saved to
  `history-file`."
  [{:keys [prompt shown-prompt hist history-file]}]
  (let [plain (or prompt "> ")
        shown (or shown-prompt plain)
        res (program/run {:init (new-state @hist)
                          :update entry-update
                          :view (partial view-str plain shown)
                          :ctrl-c :message
                          :bracketed-paste true
                          ;; pinned: the background query runs at every prompt, and
                          ;; it eats keys typed before the prompt shows
                          :dark-background? true})]
    (println) (flush)
    (let [[kind text] (:done res)]
      (case kind
        :submit (do (swap! hist add-history history-file text) text)
        :eof nil
        :cancel :cancel
        :expand :expand
        nil))))

;; ---------------------------------------------------------------- pager

(def ^:private close-keys ["q" "esc" "ctrl+o" "enter" "ctrl+c"])

(defn- pager-footer [vp]
  (let [n (vp/viewport-line-count vp) h (:height vp)
        pct (if (<= n h) 100 (int (* 100 (vp/viewport-scroll-percent vp))))]
    (style/render (style/style :faint true)
                  (str " q or Ctrl-O closes · ↑ ↓ PgUp PgDn g G scroll · " pct "%"))))

(defn pager-update
  "Pure: one charm message -> [pager-state cmd]. A resize sizes the viewport to the
  screen less the footer row; a close key quits; anything else scrolls."
  [{:keys [vp] :as st} m]
  (cond
    (msg/window-size? m)
    [(assoc st :vp (vp/viewport-set-dimensions vp (:width m) (max 1 (dec (:height m))))) nil]

    (some #(msg/key-match? m %) close-keys) [st program/quit-cmd]

    :else (let [[vp' _] (vp/viewport-update vp m)] [(assoc st :vp vp') nil])))

(defn pager-view [{:keys [vp]}] (str (vp/viewport-view vp) "\n" (pager-footer vp)))

(defn page!
  "Show `content` full screen on the alternate screen until a close key, then put
  the screen back as it was, so the conversation reappears untouched."
  [content]
  (program/run {:init {:vp (vp/viewport content :height (max 1 (dec (height))))}
                :update pager-update
                :view pager-view
                :alt-screen true
                :overflow :bottom
                :ctrl-c :message
                :dark-background? true})
  nil)

;; ---------------------------------------------------------------- selection list

(defn fuzzy-score
  "Pure: a case-insensitive subsequence score. Lower scores rank first; nil rejects."
  [query candidate]
  (let [q (str/lower-case query) s (str/lower-case candidate)]
    (if (empty? q)
      [0 0 0 0]
      (->> (range (count s))
           (keep (fn [start]
                   (when (= (first q) (nth s start))
                     (loop [qi 1 end start]
                       (if (= qi (count q))
                         [(if (= q s) 0 1)
                          (- (inc (- end start)) (count q)) start (count s)]
                         (when-let [next-pos (str/index-of s (str (nth q qi)) (inc end))]
                           (recur (inc qi) next-pos)))))))
           sort first))))

(defn fuzzy-matches
  "Pure: rank names by match score, then name. Empty queries retain catalog order."
  [query items]
  (if (empty? query)
    (vec items)
    (->> items
         (keep #(when-let [score (fuzzy-score query %)] [score (str/lower-case %) %]))
         sort (mapv last))))

(defn- picker-fit [{:keys [matches index offset rows] :as st}]
  (let [index (max 0 (min index (dec (count matches))))
        offset (max 0 (min offset index (max 0 (- (count matches) rows))))
        offset (if (>= index (+ offset rows)) (inc (- index rows)) offset)]
    (assoc st :index index :offset offset)))

(defn picker-state
  "Pure: create a picker with the current item selected."
  [title items current columns lines]
  (let [items (vec (distinct items))]
    (picker-fit {:title title :items items :current current :query "" :matches items
                 :index (or (first (keep-indexed #(when (= %2 current) %1) items)) 0)
                 :offset 0 :rows (max 1 (- lines 4)) :columns (max 1 columns)})))

(defn picker-key
  "Pure: apply a key. Enter selects; Escape and Ctrl-C cancel without a value."
  [{:keys [query matches index] :as st} key]
  (if (:done st)
    st
    (let [[k arg] (if (vector? key) key [key])
          query' (case k
                   :insert (str query (str/replace arg #"[\p{Cntrl}]" ""))
                   :backspace (subs query 0 (max 0 (dec (count query))))
                   :kill-start ""
                   query)]
      (cond
        (contains? #{:escape :ctrl-c} k) (assoc st :done [:cancel])
        (= k :enter) (if-let [value (get matches index)] (assoc st :done [:select value]) st)
        (not= query query') (picker-fit (assoc st :query query' :index 0 :offset 0
                                               :matches (fuzzy-matches query' (:items st))))
        (= k :up) (picker-fit (update st :index dec))
        (= k :down) (picker-fit (update st :index inc))
        :else st))))

(defn picker-update
  "Pure: handle a charm message without an input loop."
  [st m]
  (let [next-state (cond
                     (msg/window-size? m) (picker-fit (assoc st :columns (max 1 (:width m))
                                                             :rows (max 1 (- (:height m) 4))))
                     (msg/paste? m) (picker-key st [:insert (str (:text m))])
                     (msg/key-match? m "esc") (picker-key st :escape)
                     :else (picker-key st (press->key m)))]
    [next-state (when (:done next-state) program/quit-cmd)]))

(defn picker-view
  "Pure: show the query, current item, visible matches, and controls."
  [{:keys [title current query matches index offset rows columns]}]
  (let [clip (fn [s] (let [s (str/replace (str s) #"[\p{Cntrl}]" "")]
                       (subs s 0 (min columns (count s)))))
        visible (subvec matches offset (min (count matches) (+ offset rows)))
        lines (concat [(str title " · current: " current) (str "Search: " query)]
                      (if (seq visible)
                        (map-indexed (fn [i item]
                                       (str (if (= index (+ offset i)) "> " "  ")
                                            (if (= item current) "* " "  ") item)) visible)
                        ["No matches"])
                      [(str (count matches) " matches · * current")
                       "↑ ↓ move · Enter selects · Esc/Ctrl-C cancels"])]
    (str/join "\n" (map clip lines))))

(defn pick!
  "Run a charm selection list after the prompt exits. Return nil on cancel."
  [title items current]
  (let [result (program/run {:init (picker-state title items current (width) (height))
                             :update picker-update :view picker-view
                             :alt-screen true :overflow :bottom
                             :ctrl-c :message :bracketed-paste true :dark-background? true})
        [action value] (:done result)]
    (when (= action :select) value)))
