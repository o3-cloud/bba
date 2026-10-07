(ns bba.tui
  "A small line editor for the interactive prompt: raw-mode keys, history, multi-line input, paste.
  Keys -> edit state is pure (`handle-key`); so is screen layout (`cursor-at`)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def cont "  ")  ; prefix for continuation lines

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

;; ---------------------------------------------------------------- layout

(defn display
  "The plain text shown on screen for prompt + buffer (continuation lines get `cont`)."
  [prompt s]
  (str prompt (str/replace s "\n" (str "\n" cont))))

(defn display-index
  "Index in (display prompt buf) of buffer index `pos`."
  [prompt buf pos]
  (+ (count prompt) pos (* (count cont) (count (re-seq #"\n" (subs buf 0 pos))))))

(defn cursor-at
  "Pure: [row col] where the next character after `s` goes, wrapping at `w` columns."
  [s w]
  (reduce (fn [[r c] ch]
            (cond (= ch \newline) [(inc r) 0]
                  (>= c (dec w)) [(inc r) 0]
                  :else [r (inc c)]))
          [0 0] s))

;; ---------------------------------------------------------------- read loop

(defn- read-key
  "Read one key from `in` (a UTF-8 Reader in raw mode)."
  [^java.io.Reader in]
  (let [c (.read in)]
    (case c
      -1 [:ctrl-d]
      (13 10) :enter
      (127 8) :backspace
      1 :home, 5 :end, 2 :left, 6 :right, 16 :up, 14 :down
      3 :ctrl-c, 4 :ctrl-d, 11 :kill-end, 21 :kill-start, 23 :kill-word, 12 :clear
      27 (let [c2 (.read in)]
           (case c2
             (13 10) :newline                                ; Alt+Enter
             79 (case (char (.read in)) \A :up \B :down \C :right \D :left \H :home \F :end nil)
             91 (let [params (loop [sb (StringBuilder.)]
                               (let [ch (char (.read in))]
                                 (if (<= 0x40 (int ch) 0x7e) (str sb ch) (recur (.append sb ch)))))]
                  (case params
                    "A" :up, "B" :down, "C" :right, "D" :left
                    ("H" "1~" "7~") :home, ("F" "4~" "8~") :end, "3~" :delete
                    "200~" [:insert (loop [sb (StringBuilder.)]          ; bracketed paste
                                      (let [s (str sb)]
                                        (if (str/ends-with? s "\u001b[201~")
                                          (str/replace (subs s 0 (- (count s) 6)) #"\r\n?" "\n")
                                          (recur (.append sb (char (.read in)))))))]
                    nil))
             nil))
      (if (>= c 32) [:insert (str (char c))] nil))))

(defn- render!
  "Redraw prompt + buffer. `row` = cursor row (from the input start) after the last draw. Returns the new row."
  [{:keys [buf pos]} prompt shown-prompt w row]
  (let [shown (display prompt buf)
        [er ec] (cursor-at shown w)
        [cr cc] (cursor-at (subs shown 0 (display-index prompt buf pos)) w)
        out (StringBuilder.)]
    (.append out (str "\r" (when (pos? row) (str "\u001b[" row "A")) "\u001b[J"))
    (.append out (str shown-prompt (str/replace (subs shown (count prompt)) "\n" "\r\n")))
    (when (and (zero? ec) (pos? er) (not (str/ends-with? shown "\n")))
      (.append out " \r\u001b[K"))                                  ; leave the pending-wrap state
    (when (> er cr) (.append out (str "\u001b[" (- er cr) "A")))
    (.append out (str "\r" (when (pos? cc) (str "\u001b[" cc "C"))))
    (print (str out)) (flush)
    cr))

(defn read-line!
  "Read one entry with editing. Returns the text, nil on EOF, or :cancel on Ctrl-C at an empty prompt.
  `hist` is an atom holding the history vector; entries are saved to `history-file`."
  [{:keys [prompt shown-prompt hist history-file]}]
  (let [saved (stty "-g")
        in (java.io.InputStreamReader. System/in "UTF-8")
        w (width)
        shown-prompt (or shown-prompt prompt)]
    (try
      (stty "raw" "-echo")
      (print "\u001b[?2004h")
      (loop [st (new-state @hist) row (render! (new-state @hist) prompt shown-prompt w 0)]
        (let [key (read-key in)
              st (if (= key :clear) (do (print "\u001b[H\u001b[2J") st) (handle-key st key))
              row (render! (if (:done st) (assoc st :pos (count (:buf st))) st) prompt shown-prompt w
                           (if (= key :clear) 0 row))]
          (if-let [[kind text] (:done st)]
            (do (print "\r\n") (flush)
                (case kind
                  :submit (do (swap! hist add-history history-file text) text)
                  :eof nil
                  :cancel :cancel))
            (recur st row))))
      (finally
        (print "\u001b[?2004l") (flush)
        (stty saved)))))
