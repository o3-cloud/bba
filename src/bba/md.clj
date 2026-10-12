(ns bba.md
  "Rendered markdown for a terminal: headings, lists, quotes, rules, fenced code
  blocks, tables and inline spans become styled text, wrapped to the terminal
  width. The renderer is pure: `md-line` maps [context line] to [context' rows],
  so a streaming caller carries the open code block from one line to the next, and
  the caller decides whether to render at all (`:render`) and how wide (`:width`).
  Widths are display cells measured through charm, so ANSI escapes and
  double-width characters cannot shift the layout.

  A palette maps a syntax element to a function of the text. A palette function
  must not change the display width of what it styles -- every palette here uses
  escape sequences, which measure as zero cells -- because the renderer sizes rows,
  panels and table columns from the text. Colours come from `bba.ui`, whose helpers
  are identity when colour is off, so one code path renders a terminal and a pipe."
  (:require [clojure.string :as str]
            [bba.ui :as ui]
            [charm.style.core :as charm]))

;; ---------------------------------------------------------------- context

(def default-palette
  "One style function per syntax element, each taking and returning a string.
  With colour off the `bba.ui` helpers are identity, so the markup is still
  removed and the text still wrapped."
  {:h1      (fn [s] (ui/underline (ui/bold s)))
   :h2      (fn [s] (ui/magenta (ui/bold s)))
   :h3      (fn [s] (ui/cyan (ui/bold s)))
   :h4      ui/bold
   :bullet  ui/dim
   :quote   ui/dim
   :rule    ui/dim
   :fence   ui/dim
   :code    ui/yellow
   :bold    ui/bold
   :italic  ui/italic
   :strike  ui/strike
   :link    ui/cyan
   :url     ui/dim
   :table   ui/dim})

(defn context
  "Renderer state, threaded through `md-line`. :width is the terminal width in
  cells (default 80); :render false returns every line untouched, which is what a
  pipe gets; :palette replaces the styles; :inline false takes the text literally,
  which is what a fenced block needs; :fence records an open code block."
  ([] (context {}))
  ([{:keys [width render palette inline]}]
   {:width   (or width 80)
    :render  (if (nil? render) true (boolean render))
    :palette (or palette default-palette)
    :inline  (if (nil? inline) true (boolean inline))
    :fence   false}))

(defn- pal
  "The palette function for key `k` of `ctx`, or identity."
  [ctx k]
  (or (get (:palette ctx) k) identity))

(defn- styled
  "`s` with palette style `k` of `ctx` applied."
  [ctx k s]
  ((pal ctx k) s))

(defn width-of
  "Display width of `s` in terminal cells: escape sequences count zero, CJK and
  emoji two, combining marks zero."
  [s]
  (charm/string-width (str s)))

(defn fit
  "The longest prefix of `s` that fits in `cells` display cells, never splitting a
  grapheme. A streaming caller prints this much of a partial line, so the terminal
  does not wrap the row itself and the caller can still erase it."
  [s cells]
  (let [cells (max 0 cells)]
    (if (<= (width-of s) cells)
      (str s)
      (loop [gs (re-seq #"\X" (str s)) acc "" used 0]
        (if-let [g (first gs)]
          (let [gw (width-of g)]
            (if (<= (+ used gw) cells)
              (recur (rest gs) (str acc g) (+ used gw))
              acc))
          acc)))))

;; ---------------------------------------------------------------- inline spans

(def ^:private inline-rules
  "The inline syntax, tried in order. The rule that matches earliest wins; on a tie
  the earlier rule wins, so a code span beats the emphasis inside it. An image is
  listed after a link so that a link pattern cannot eat its bracket."
  [[:code   #"`([^`]+)`"]
   [:link   #"\[([^\]]+)\]\(([^ )]+)[^)]*\)"]
   [:image  #"!\[([^\]]*)\]\(([^ )]+)[^)]*\)"]
   [:bold   #"\*\*([^*]+)\*\*"]
   [:bold   #"__([^_]+)__"]
   [:strike #"~~([^~]+)~~"]
   [:italic #"(?<![\w*])\*([^*\n]+?)\*(?![\w*])"]])

(defn- earliest
  "The earliest match of any inline rule in `text` at or after `from`, as
  [start end key match]; nil when no rule matches. Matches are sorted by position,
  and `sort-by` is stable, so when two rules match at the same place the earlier
  rule in `inline-rules` wins."
  [text from]
  (->> inline-rules
       (keep (fn [[k re]]
               (let [m (re-matcher re text)]
                 (when (.find m (int from))
                   [(.start m) (.end m) k m]))))
       (sort-by first)
       first))

(defn- osc8
  "`label` as an OSC 8 hyperlink when colour is on: terminals that know the
  sequence make the text clickable, terminals that do not drop it, and the width
  measurement ignores it. With colour off the label comes back bare."
  [url label]
  (if (and (ui/enabled?) (seq url))
    (str "\u001b]8;;" url "\u001b" (char 92) label "\u001b]8;;\u001b" (char 92))
    label))

(defn- group
  "Capture group `n` of a matcher, or nil when the rule has no such group."
  [^java.util.regex.Matcher m n]
  (when (<= n (.groupCount m)) (.group m (int n))))

(defn- piece
  "The spans one matched inline rule contributes, as [palette-key text]."
  [k m]
  (let [g1 (group m 1) g2 (group m 2)]
    (case k
      :code   [[:code g1]]
      :bold   [[:bold g1]]
      :italic [[:italic g1]]
      :strike [[:strike g1]]
      :image  [[:url (or g1 g2)]]
      :link   (cond-> [[:link (osc8 g2 g1)]]
                (not= g1 g2) (conj [:url (str " <" g2 ">")])))))

(defn spans
  "Split `text` into [palette-key text] spans, applying each inline rule at most
  once at a position (spans do not nest). Empty pieces are dropped, so a string
  that is entirely one match comes back as a single span."
  [text]
  (->> (loop [out [] i 0]
         (if-let [[start end k m] (earliest text i)]
           (recur (-> out (conj [nil (subs text i start)]) (into (piece k m))) end)
           (conj out [nil (subs text i)])))
       (remove (fn [[_ t]] (empty? (str t))))  ; not blank?: a space between two spans is text
       vec))

(defn plain-of
  "`s` with its inline markup removed but its styling left behind: the text a
  terminal would show for it. Used to size a column or a panel from what will
  really be printed."
  [s]
  (str/join (map second (spans s))))

(defn- to-string
  "Spans to a string, with the palette applied."
  [ctx spans]
  (apply str (map (fn [[k t]] ((pal ctx k) t)) spans)))

;; ---------------------------------------------------------------- wrapping

(defn- blank-str [n] (apply str (repeat (max 0 (int n)) (char 32))))

(defn- tokens
  "`text` as [palette-key token] pairs, splitting spans on whitespace so a row can
  be broken between words. The keys survive, so a wrapped bold phrase keeps its
  style on every row. Runs of whitespace become one space, because a row always
  fits on one terminal line. With `:inline` false in `ctx` the text is taken
  literally, which a fenced code block needs: there a backtick or an asterisk is a
  character, not markup."
  [ctx text]
  (let [flat (str/replace (str text) #"\s+" " ")]
    (->> (if (:inline ctx) (spans flat) [[nil flat]])
         (mapcat (fn [[k t]] (map (fn [p] [k p]) (re-seq #"(?s)\S+|\s+" t))))
         vec)))

(defn- trim-tail
  "`cur` without trailing whitespace tokens: a row never ends in the space it was
  broken at."
  [cur]
  (vec (reverse (drop-while (fn [[_ t]] (str/blank? (str t))) (reverse cur)))))

(defn wrap-rows
  "`text` wrapped to rows of at most `usable` display cells, with the palette
  applied. A word longer than a whole row is cut at the last cell that fits, so a
  long path or URL does not rely on the terminal's own wrapping -- which the caller
  could not count. The space a row is broken at is dropped, so no row ends in
  padding and the rows are safe to join."
  [ctx usable text]
  (let [usable (max 1 (int usable))
        row (fn [cur] (to-string ctx (trim-tail cur)))]
    (loop [toks (tokens ctx text) rows [] cur [] curw 0]
      (if-let [[k t] (first toks)]
        (let [w (width-of t)]
          (cond
            (str/blank? t)
            (cond
              (zero? curw) (recur (rest toks) rows cur 0)
              (> (+ curw w) usable) (recur (rest toks) (conj rows (row cur)) [] 0)
              :else (recur (rest toks) rows (conj cur [k t]) (+ curw w)))

            (<= (+ curw w) usable)
            (recur (rest toks) rows (conj cur [k t]) (+ curw w))

            (pos? curw)
            (recur toks (conj rows (row cur)) [] 0)

            :else
            (let [head (fit t usable)]
              (if (empty? head)
                ;; a single grapheme wider than the row: give the row to it, or the
                ;; loop would never make progress
                (recur (rest toks) (conj rows (to-string ctx [[k t]])) [] 0)
                (recur (cons [k (subs t (count head))] (rest toks))
                       (conj rows (to-string ctx [[k head]])) [] 0)))))
        (cond (seq (trim-tail cur)) (conj rows (row cur))
              (seq rows) rows
              :else [""])))))

(defn- hanging
  "`text` wrapped under a marker onto rows of `:width` cells, indented by `indent`.
  The first row carries the styled `prefix`, later rows are indented past it by
  `plain-width` cells -- the marker's width without styling, since a palette may add
  escapes that occupy no cells and must not shift the alignment."
  [ctx indent plain-width prefix text]
  (let [pad (max 1 (- (:width ctx) indent plain-width))
        cont (blank-str (+ indent plain-width))]
    (str/join "\n"
              (map-indexed (fn [i r] (if (zero? i)
                                       (str (blank-str indent) prefix r)
                                       (str cont r)))
                           (wrap-rows ctx pad text)))))

(defn- text-block
  "A paragraph: wrapped from `indent`, with no marker."
  [ctx indent text]
  (hanging ctx indent 0 "" text))

;; ---------------------------------------------------------------- line shapes

(defn- indent-of [line] (- (count line) (count (str/triml line))))

(def ^:private fence-re
  (re-pattern (str "^" (char 92) "s*(```+|~~~+)" (char 92) "s*(" (char 92) "S*)" (char 92) "s*$")))

(defn- fence-marker [line] (re-matches fence-re line))

(defn- heading-parts [line] (re-matches #"^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$" line))

(def ^:private hr-re #"^\s{0,3}([-*_])(\s*\1){2,}\s*$")

(defn- hr? [line] (boolean (re-matches hr-re line)))

(def ^:private bullet-re #"^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$")

(defn- bullet-parts [line] (re-matches bullet-re line))

(defn- quote-parts [line] (re-matches #"^\s{0,3}>\s?(.*)$" line))

(defn- split-row
  "A table row as trimmed cell strings, with the optional outer pipes removed."
  [line]
  (let [l (str/trim line)
        l (if (str/starts-with? l "|") (subs l 1) l)
        l (if (str/ends-with? l "|") (subs l 0 (dec (count l))) l)]
    (mapv str/trim (str/split l #"\|"))))

(defn table-row? [line]
  (and (not (str/blank? line)) (str/includes? line "|") (seq (split-row line))))

(defn- table-delim? [line]
  (let [cs (split-row line)]
    (and (seq cs) (every? #(re-matches #":?-+:?" %) cs))))

(defn- align-of [cell]
  (let [c (str/trim cell) l (str/starts-with? c ":") r (str/ends-with? c ":")]
    (if (and l r) :center (if r :right :left))))

;; ---------------------------------------------------------------- fenced code

(defn- panel
  "One panel line: a dim marker, the content, dim padding, then a closing bar at the
  last column, so every line of a code block meets the corners above and below."
  [ctx lead content plain-width]
  (let [pad (blank-str (max 0 (- (:width ctx) (width-of lead) plain-width 1)))]
    (str (styled ctx :fence lead) content (styled ctx :fence (str pad "│")))))

(defn- code-row
  "One line of a fenced block as panel rows: the text as it came, wrapped under a
  two-cell marker and padded to the width so the panel stays closed. Nothing here is
  interpreted -- with `:inline` false a backtick or an asterisk is a character -- and
  the code style covers the whole row, so no word is styled on its own."
  [ctx line]
  (let [w (:width ctx)
        line (str/replace (str/trimr (str line)) "\t" "  ")
        ind (min (indent-of line) (quot w 2))  ; code keeps its indentation, within reason
        lead (blank-str ind)
        text (str/replace (str/triml line) #"\s+" " ")]
    (mapv (fn [r] (let [r (str lead r)] (panel ctx "│ " (styled ctx :code r) (width-of r))))
          (wrap-rows (assoc ctx :inline false :palette {}) (max 1 (- w 3 ind)) text))))

(defn- fence-open
  "The opening marker for a fence whose info string is `info`: a left corner, the
  language, and a rule out to the right corner, as one styled run."
  [ctx info]
  (let [w (:width ctx)
        top (str "╭─" info (char 32))
        rule (apply str (repeat (max 0 (- w (width-of top) 1)) "─"))]
    (styled ctx :fence (str top rule "╮"))))

(defn- fence-close [ctx]
  (styled ctx :fence (str "╰" (apply str (repeat (max 0 (- (:width ctx) 2)) "─")) "╯")))

;; ---------------------------------------------------------------- md-line

(defn md-line
  "One markdown line -> [context' rows]. `ctx` carries the open code block across
  calls, so this works line by line while a model streams. With `:render` false the
  line comes back unchanged, which is what a pipe wants."
  [ctx line]
  (if-not (:render ctx)
    [ctx [line]]
    (let [line (str line) ind (indent-of line)]
      (cond
        (fence-marker line)
        (let [[_ _ info] (fence-marker line)]
          (if (:fence ctx)
            [(assoc ctx :fence false) [(fence-close ctx)]]
            [(assoc ctx :fence true) [(fence-open ctx info)]]))

        (:fence ctx) [ctx (code-row ctx line)]

        (str/blank? line) [ctx [""]]

        (hr? line)
        [ctx [(styled ctx :rule (apply str (repeat (:width ctx) "─")))]]

        ;; the hashes are dropped and the level's style covers the whole text; inline
        ;; markup is removed first, since an inline style would reset the heading's
        (heading-parts line)
        (let [[_ hashes text] (heading-parts line)
              key ([:h1 :h2 :h3 :h4] (min 3 (dec (count hashes))))]
          [ctx (mapv #(styled ctx key %)
                     (str/split (text-block ctx (min 3 ind) (plain-of text)) #"\n"))])

        (bullet-parts line)
        (let [[_ lead marker text] (bullet-parts line)
              ind (max (count lead) (min 3 ind))
              mark (str (if (re-matches #"\d{1,9}[.)]" marker) (str/trimr marker) "•")
                        (char 32))]
          [ctx (str/split (hanging ctx ind (count mark) (styled ctx :bullet mark) text)
                          #"\n")])

        (quote-parts line)
        (let [[_ text] (quote-parts line)
              mark "▏ "]
          [ctx (str/split (hanging ctx 0 (count mark) (styled ctx :quote mark) text) #"\n")])

        :else [ctx (str/split (text-block ctx (min 3 ind) line) #"\n")]))))

;; ---------------------------------------------------------------- tables

(defn- cell
  "One table cell, styled and padded to its column width under `align`. A cell that
  fits keeps its inline styling; one that has to be cut is taken from its plain
  text, because cutting a string with escapes in it would leave them unbalanced."
  [ctx align w text]
  (let [plain (plain-of text)
        body (if (<= (width-of plain) w) text (fit plain w))
        gap (max 0 (- w (width-of (plain-of body))))
        pad (blank-str gap)
        styled-body (to-string ctx (spans body))]
    (case align
      :right (str pad styled-body)
      :center (let [l (quot gap 2)] (str (blank-str l) styled-body (blank-str (- gap l))))
      (str styled-body pad))))

(defn- table
  "`rows` (a vector of cell vectors) as an aligned table, one string per row, each
  column sized to its widest visible cell and the row capped to fit the width."
  [ctx aligns rows]
  (let [n (apply max (map count rows))
        cols (mapv #(mapv (fn [r] (get r % "")) rows) (range n))
        want (mapv #(apply max 0 (map (comp width-of plain-of) %)) cols)
        budget (- (:width ctx) (inc (* 3 n)))  ; "│ a │ b │": 3 cells per column, plus a leading bar
        fits (fn [ws] (<= (reduce + ws) (max 1 budget)))
        widths (loop [ws want]
                 (if (fits ws)
                   ws
                   (let [i (first (sort-by #(- (get ws %)) (range n)))]
                     (if (or (zero? (get ws i)) (<= budget 0))
                       ws
                       (recur (update ws i dec))))))
        bar (styled ctx :table "│")
        line (fn [cells]
               (str bar (char 32)
                    (str/join (str (char 32) bar (char 32))
                              (map #(cell ctx (get aligns %) (get widths %) (get cells %))
                                   (range n)))
                    (char 32) bar))
        border (fn [l m r]  ; a horizontal rule with the column joins in place
                 (styled ctx :table (str l (str/join m (map #(apply str (repeat (+ 2 %) "─")) widths)) r)))
        [head & body] rows]
    (-> [(border "┌" "┬" "┐") (line (mapv #(if (str/blank? %) % (str "**" (plain-of %) "**")) head))]
        (cond-> (seq body) (conj (border "├" "┼" "┤")))
        (into (map line body))
        (conj (border "└" "┴" "┘")))))

(defn- table-at
  "Where a pipe table starts in `lines` at or after index `i`: the index of its
  delimiter row, or nil. A header row alone is not a table."
  [lines i]
  (let [next-line (get lines (inc i))]
    (when (and (table-row? (get lines i)) next-line (table-delim? next-line))
      (inc i))))

(defn render
  "A whole markdown document as a terminal string. Table detection needs one line of
  lookahead, which the streaming `md-line` cannot do, so this walks the lines and
  hands everything else back to `md-line`."
  [ctx text]
  (if-not (:render ctx)
    text
    (let [lines (vec (str/split-lines (str text)))]
      (loop [i 0 ctx ctx out []]
        (if (>= i (count lines))
          (str/join "\n" out)
          (let [line (get lines i)]
            (if-let [delim (when-not (:fence ctx) (table-at lines i))]
              (let [aligns (mapv align-of (split-row (get lines delim)))
                    end (loop [j (inc delim)]
                          (if (and (< j (count lines))
                                   (table-row? (get lines j))
                                   (not (table-delim? (get lines j))))
                            (recur (inc j))
                            j))
                    rows (into [(split-row line)]
                               (map split-row (subvec lines (inc delim) end)))]
                (recur end ctx (into out (table ctx aligns rows))))
              (let [[ctx' rows] (md-line ctx line)]
                (recur (inc i) ctx' (into out rows))))))))))

(defn stream-row
  "For an unfinished streaming line: [context' rendered complete?]. Only the last row
  of a line can still change while more characters arrive, so a wrapped line returns
  the rows that are already complete and holds the last one back; `complete?` says
  that nothing was held back. A single row is always safe to print, even when an open
  fence means the line itself is not finished. A caller that keeps `context'` holds a
  fenced block open across lines. The function is pure, so reprinting a line gives
  the same rows."
  [ctx line]
  (let [[ctx' rows] (md-line ctx line)
        wrapped? (< 1 (count rows))
        complete? (and (not wrapped?) (not (:fence ctx')))]
    [ctx' (str/join "\n" (if wrapped? (butlast rows) rows)) complete?]))

(defn rows-str
  "`rows` (as `md-line` returns them) joined into one printable string."
  [rows]
  (str/join "\n" rows))
