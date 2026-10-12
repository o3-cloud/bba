(ns md-test
  "Markdown rendering: inline spans, blocks, wrapping, tables and the streaming
  row logic. No terminal is needed. Two palettes are used: a tagging palette, which
  names the element a span got, for assertions about styling, and an empty palette,
  which leaves the text bare, for assertions about width and alignment (a tagging
  palette adds visible characters, which would count as cells)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [bba.md :as md]
            [bba.ui :as ui]))

(def tags
  {:bold    #(str "<b>" % "</b>")
   :italic  #(str "<i>" % "</i>")
   :code    #(str "<c>" % "</c>")
   :strike  #(str "<s>" % "</s>")
   :link    #(str "<a>" % "</a>")
   :url     #(str "<u>" % "</u>")
   :h1      #(str "<h1>" % "</h1>")
   :h2      #(str "<h2>" % "</h2>")
   :h3      #(str "<h3>" % "</h3>")
   :h4      #(str "<h4>" % "</h4>")
   :bullet  #(str "<m>" % "</m>")
   :quote   #(str "<q>" % "</q>")
   :rule    #(str "<r>" % "</r>")
   :fence   #(str "<f>" % "</f>")
   :table   #(str "<t>" % "</t>")})

(defn- one
  "One line rendered; rows joined. `palette` defaults to the tagging palette."
  ([line] (one line 20 tags))
  ([line w] (one line w tags))
  ([line w palette] (md/rows-str (second (md/md-line (md/context {:width w :palette palette}) line)))))

(defn- all
  "A whole document rendered. `palette` defaults to the tagging palette."
  ([text] (all text 20 tags))
  ([text w] (all text w tags))
  ([text w palette] (md/render (md/context {:width w :palette palette}) text)))

(defn- lines-of [s] (str/split-lines s))

(use-fixtures :each (fn [t] (let [was (ui/enabled?)]
                              (ui/enable! false)
                              (try (t) (finally (ui/enable! was))))))

;; ---------------------------------------------------------------- inline spans

(deftest inline-spans-name-their-element
  (is (= [[:bold "b"]] (md/spans "**b**")))
  (is (= [[:bold "b"]] (md/spans "__b__")) "underscores work too")
  (is (= [[:italic "i"]] (md/spans "*i*")))
  (is (= [[:strike "s"]] (md/spans "~~s~~")))
  (is (= [[:code "c"]] (md/spans "`c`")))
  (is (= [[nil "a "] [:bold "b"] [nil " c"]] (md/spans "a **b** c"))))

(deftest a-code-span-wins-over-the-emphasis-inside-it
  (is (= [[:code "a **b** c"]] (md/spans "`a **b** c`"))))

(deftest a-link-keeps-its-target
  (is (= [[:link "t"] [:url " <u>"]] (md/spans "[t](u)"))
      "the target is shown when it differs from the label")
  (is (= [[:link "u"]] (md/spans "[u](u)")) "and not repeated when it is the same"))

(deftest emphasis-needs-a-boundary
  (is (= [[nil "a*b*c"]] (md/spans "a*b*c")) "mid-word asterisks are not emphasis")
  (is (= [[nil "2 * 3"]] (md/spans "2 * 3")) "a lone asterisk is not emphasis"))

;; ---------------------------------------------------------------- width

(deftest width-counts-cells-not-characters
  (is (= 3 (md/width-of "abc")))
  (is (= 4 (md/width-of "你好")) "wide characters are two cells")
  (is (= 2 (md/width-of "\u001b[31mhi\u001b[0m")) "escapes are zero wide")
  (is (= 9 (md/width-of "<b>hi</b>"))
      "a tagging palette is visible text: only escape-based styling is free")
  (is (= "abc" (md/plain-of "a**b**c")) "plain-of drops the markup, not the letters"))

(deftest fit-never-splits-a-wide-character
  (is (= "你好" (md/fit "你好" 4)))
  (is (= "你" (md/fit "你好" 3)) "one wide character fits in three cells")
  (is (= "" (md/fit "你好" 1)))
  (is (= "abc" (md/fit "abcd" 3))))

(deftest wrapping-fills-the-width
  (is (= ["alpha beta gamma" "delta epsilon"]
         (md/wrap-rows (md/context {:palette {}}) 16 "alpha beta gamma delta epsilon")))
  (is (= ["alpha beta" "gamma"]
         (md/wrap-rows (md/context {:palette {}}) 10 "alpha beta gamma"))
      "a row takes the last word that fits, not the last that overflows"))

(deftest a-word-longer-than-the-row-is-cut
  (is (= ["abcdefgh" "ijkl"] (md/wrap-rows (md/context {:palette {}}) 8 "abcdefghijkl"))))

(deftest no-row-needs-the-terminals-own-wrap
  (let [w 24
        text (str/join " " (map #(str "word" %) (range 40)))
        rows (md/wrap-rows (md/context {:palette {}}) w text)]
    (is (< 1 (count rows)))
    (is (every? #(<= (md/width-of %) w) rows))
    (is (= text (str/join " " rows)) "no word is lost, doubled or reordered")))

(deftest a-row-is-not-widened-by-escape-styling
  (let [p {:bold (fn [s] (str "\u001b[1m" s "\u001b[0m"))}
        rows (md/wrap-rows (md/context {:width 20 :palette p}) 10 "a **bb** c d")]
    (is (= ["a \u001b[1mbb\u001b[0m c d"] rows))))

;; ---------------------------------------------------------------- blocks

(deftest headings
  (is (= "<h1>Hi</h1>" (one "# Hi")) "the hashes are not shown")
  (is (= "<h2>Hi</h2>" (one "## Hi")))
  (is (= "<h3>Hi</h3>" (one "### Hi")))
  (is (= "<h4>Hi</h4>" (one "#### Hi")))
  (is (= "<h4>Hi</h4>" (one "###### Hi")) "levels below four share the level-four style")
  (is (= "<h3>Hi</h3>" (one "### Hi ###")) "a closing run of hashes is dropped")
  (is (= "<h2>Use the tool</h2>" (one "## Use **the** `tool`" 40)) "inline markup is removed inside a heading")
  (is (= "Use <b>the</b> <c>tool</c>" (one "Use **the** `tool`" 40)) "the space between two spans is kept")
  (is (= ["<h2>a long heading</h2>" "<h2>that wraps</h2>"]
         (str/split-lines (one "## a long heading that wraps" 16))) "every wrapped row keeps the style")
  (is (= "#notaheading" (one "#notaheading"))))

(deftest bullets-get-a-marker
  (is (= "<m>• </m>one" (one "- one")))
  (is (= "<m>• </m>one" (one "* one")) "an asterisk bullet is a bullet, not emphasis")
  (is (= "<m>• </m>one" (one "+ one")))
  (is (= "<m>1. </m>one" (one "1. one")) "an ordered list keeps its number"))

(deftest a-wrapped-bullet-stays-inside-its-marker
  (let [rows (lines-of (one "- one two three" 10 {}))]
    (is (= ["• one two" "  three"] rows)
        "the continuation is indented past the marker")
    (is (every? #(<= (md/width-of %) 10) rows))))

(deftest a-nested-bullet-keeps-its-indent
  (is (= ["<m>• </m>a" "<m>• </m>b"] (lines-of (all "- a\n- b")))
      "sibling bullets do not stack")
  (is (= "  <m>• </m>b" (one "  - b")) "an indented bullet keeps two cells"))

(deftest quotes-indent-under-a-bar
  (is (= "<q>▏ </q>hi" (one "> hi")))
  (is (= ["▏ one" "  two"] (lines-of (one "> one two" 8 {})))
      "a wrapped quote lines up under its text"))

(deftest a-rule-spans-the-width
  (is (= (apply str (repeat 12 "─")) (one "---" 12 {})))
  (is (= (one "---" 12 {}) (one "***" 12 {})) "every rule character works")
  (is (= (one "---" 12 {}) (one "- - -" 12 {}))))

;; ---------------------------------------------------------------- fenced code

(deftest code-in-a-fence-keeps-its-indentation
  (is (= ["╭─ ───╮" "│ (a  │" "│   b)│" "╰─────╯"]
         (lines-of (all "```\n(a\n  b)\n```" 7 {})))
      "the two leading spaces survive, and every row meets the corners"))

(deftest a-fenced-block-is-a-panel
  (let [ctx (md/context {:width 20 :palette {}})
        [ctx a] (md/md-line ctx "```clojure")
        [ctx b] (md/md-line ctx "(inc 1)")
        [_ c] (md/md-line ctx "```")]
    (is (= ["╭─clojure ─────────╮"] a) "the info string names the block")
    (is (= ["│ (inc 1)          │"] b) "and the body is padded to a closing bar")
    (is (= [(str "╰" (apply str (repeat 18 "─")) "╯")] c))))

(deftest markup-inside-a-fence-is-left-alone
  (let [[ctx _] (md/md-line (md/context {:width 40 :palette tags}) "```")
        [_ rows] (md/md-line ctx "**not bold** and `not code`")
        row (first rows)]
    (is (not (str/includes? row "<b>")) "** stays literal in a code block")
    (is (= 1 (count (re-seq #"<c>" row))) "the whole row is one code run, not a nested span")
    (is (= "│ **not bold** and `not code`"
           (str/trimr (str/replace (str/replace row #"</?[cf]>" "") #"│$" "")))
        "the characters arrive unchanged")))

(deftest a-fence-with-no-info-string-has-no-label
  (is (= ["╭─ ────────╮"] (lines-of (one "```" 12 {})))))

(deftest a-long-code-line-wraps-inside-the-panel
  (let [[ctx _] (md/md-line (md/context {:width 12 :palette {}}) "```")
        [_ rows] (md/md-line ctx "aaa bbb ccc")]
    (is (= ["│ aaa bbb" "│ ccc"] (mapv #(str/trimr (str/replace % #"│$" "")) rows)) "continuation rows keep the marker")
    (is (every? #(= 12 (md/width-of %)) rows) "and every row is padded to the width")))
;; ---------------------------------------------------------------- tables

(deftest a-table-is-aligned-and-only-fits-the-width
  (let [t (str/join "
" ["| Name | Qty |"
   "|:-----|----:|"
   "| apple | 3 |"
   "| pear | 12 |"])
        rows (lines-of (all t 30 {}))]
    (is (= 6 (count rows)) "top, header, rule, two rows, bottom: the delimiter row is not printed")
    (is (apply = (map md/width-of rows)) "every row occupies the same cells")
    (is (str/includes? (nth rows 1) "Name"))
    (is (str/ends-with? (nth rows 3) "  3 │") "a right-aligned column lines up")
    (is (str/ends-with? (nth rows 4) " 12 │"))))

(deftest a-one-dash-delimiter-makes-a-table
  (is (= 5 (count (lines-of (all "| a | b |\n|-|-|\n| 1 | 2 |" 30 {}))))))

(deftest a-table-header-is-bold
  (is (str/includes? (all "| A |\n|---|\n| b |" 20 tags) "<b>A</b>")))

(deftest a-header-without-a-delimiter-is-not-a-table
  (is (= "| Name | Qty |" (all "| Name | Qty |" 30 {}))))

(deftest a-wide-table-is-capped-to-the-width
  (let [t (str/join "
" [(str "| " (apply str (repeat 40 "a")) " | b |")
   "|---|---|"
   "| c | d |"])
        rows (lines-of (all t 20 {}))]
    (is (= 1 (count (distinct (map md/width-of rows)))))
    (is (every? #(<= (md/width-of %) 20) rows) "no row overflows the terminal")))

(deftest inline-markup-in-a-table-cell-is-rendered
  (let [t (str/join "
" ["| A |" "|---|" "| **b** |"])
        out (all t 20 tags)]
    (is (str/includes? out "<b>b</b>"))))

;; ---------------------------------------------------------------- streaming

(deftest a-line-that-fits-is-complete-and-one-row
  (let [[_ rendered complete?] (md/stream-row (md/context {:width 20 :palette {}}) "- one")]
    (is (= "• one" rendered))
    (is (true? complete?))))

(deftest a-wrapped-line-holds-back-its-partial-row
  (let [[_ rendered complete?] (md/stream-row (md/context {:width 12 :palette {}})
                                              "- one two three four five")]
    (is (false? complete?) "a wrapped line is not safe to redraw in place")
    (is (= 2 (count (lines-of rendered))) "the rows that are already complete")))

(deftest an-open-fence-is-not-complete
  (let [[_ rendered complete?] (md/stream-row (md/context {:width 20 :palette {}}) "```clj")]
    (is (= "╭─clj ─────────────╮" rendered))
    (is (false? complete?) "the block is still open, so more rows are coming")))

(deftest stream-row-is-pure
  (let [ctx (md/context {:width 20 :palette tags})]
    (is (= (second (md/stream-row ctx "a b c")) (second (md/stream-row ctx "a b c"))))
    (is (= (second (md/stream-row ctx "- x")) (second (md/stream-row ctx "- x"))))))

;; ---------------------------------------------------------------- off switch

(deftest without-rendering-the-text-is-untouched
  (let [ctx (md/context {:render false})
        text (str/join "
" ["# H" "" "**b** `c`" "| a | b |" "|---|---|" "| 1 | 2 |"])]
    (is (= text (md/render ctx text)) "a pipe gets the markdown as it came")
    (is (= [ctx ["**b**"]] (md/md-line ctx "**b**")))))

(deftest colour-off-still-removes-the-markup
  (let [plain (fn [s] (md/render (md/context {:width 40 :palette {}}) s))]
    (is (= "a bold c" (plain "a **bold** c")) "the markers go even with no styling")
    (is (= "a code c" (plain "a `code` c")))
    (is (= "• item" (plain "- item")))))

(deftest a-table-row-starts-and-ends-at-its-own-bars
  (let [t (str/join "\n" ["| Name | Qty |" "|---|---:|" "| apple | 3 |"])
        rows (lines-of (all t 30 {}))
        bar (str (char 9474))]
    (is (= ["┌───────┬─────┐"
            "│ Name  │ Qty │"
            "├───────┼─────┤"
            "│ apple │   3 │"
            "└───────┴─────┘"] rows) "a box with no space before the first bar or after the last")
    (is (every? #(str/starts-with? % bar) (take-nth 2 (rest rows))))))
