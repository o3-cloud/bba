(ns bba.tui
  "The interactive line editor, built on charm.clj. Key presses arrive as charm
  messages; charm message -> edit state is pure (`press->key`, `handle-key`), and so
  is the rendered frame (`view-str`). charm owns raw mode, wrapping and redrawing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
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
        (and (:alt m) (contains? #{:enter} k)) :newline
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
                          :bracketed-paste true})]
    (println) (flush)
    (let [[kind text] (:done res)]
      (case kind
        :submit (do (swap! hist add-history history-file text) text)
        :eof nil
        :cancel :cancel
        nil))))
