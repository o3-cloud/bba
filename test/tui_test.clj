(ns tui-test
  "Line editor, charm bridge, streaming folds, session resume and cancel. No terminal needed."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [charm.components.viewport]
            [charm.message :as msg]
            [org.httpkit.server :as server]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.main :as main]
            [bba.md]
            [bba.toolview :as toolview]
            [bba.openai :as openai]
            [bba.provider :as provider]
            [bba.sys :as sys]
            [bba.tui :as tui]
            [bba.ui :as ui]))

(use-fixtures :each (fn [t] (ext/reset-all!) (t) (ext/reset-all!)))

(defn- keys->state [st ks] (reduce tui/handle-key st ks))

(defn- typed [s] (map (fn [c] [:insert (str c)]) s))

;; ---------------------------------------------------------------- editor keys

(deftest typing-and-cursor-moves
  (let [st (keys->state (tui/new-state []) (concat (typed "helo") [:left [:insert "l"]]))]
    (is (= "hello" (:buf st)))
    (is (= 4 (:pos st))))
  (let [st (keys->state (tui/new-state []) (concat (typed "abc") [:home :delete :end :backspace]))]
    (is (= "b" (:buf st)))))

(deftest kill-keys
  (is (= "one " (:buf (keys->state (tui/new-state []) (concat (typed "one two") [:kill-word])))))
  (is (= "" (:buf (keys->state (tui/new-state []) (concat (typed "one") [:kill-word])))) "one word, no space")
  (is (= "" (:buf (keys->state (tui/new-state []) (concat (typed "abc") [:kill-start])))))
  (is (= "a" (:buf (keys->state (tui/new-state []) (concat (typed "abc") [:home :right :kill-end]))))))

(deftest enter-submits-or-continues
  (is (= [:submit "hi"] (:done (keys->state (tui/new-state []) (concat (typed "hi") [:enter])))))
  (testing "trailing backslash makes a new line"
    (let [st (keys->state (tui/new-state []) (concat (typed "a\\") [:enter] (typed "b") [:enter]))]
      (is (= [:submit "a\nb"] (:done st)))))
  (testing "Alt+Enter makes a new line"
    (is (= [:submit "a\nb"] (:done (keys->state (tui/new-state []) (concat (typed "a") [:newline] (typed "b") [:enter])))))))

(deftest home-and-end-work-per-line
  (let [st (keys->state (tui/new-state []) (concat (typed "ab") [:newline] (typed "cd") [:home]))]
    (is (= 3 (:pos st)) "home goes to the start of the current line")
    (is (= 5 (:pos (tui/handle-key st :end))))))

(deftest ctrl-c-and-ctrl-d
  (is (= "" (:buf (keys->state (tui/new-state []) (concat (typed "x") [:ctrl-c])))) "Ctrl-C clears text")
  (is (= [:cancel] (:done (tui/handle-key (tui/new-state []) :ctrl-c))) "Ctrl-C on empty cancels")
  (is (= [:eof] (:done (tui/handle-key (tui/new-state []) :ctrl-d))) "Ctrl-D on empty is EOF")
  (is (nil? (:done (keys->state (tui/new-state []) (concat (typed "x") [:ctrl-d])))) "Ctrl-D with text does not exit"))

(deftest history-up-and-down
  (let [st (keys->state (tui/new-state ["first" "second"]) (concat (typed "draft") [:up]))]
    (is (= "second" (:buf st)))
    (is (= "first" (:buf (tui/handle-key st :up))))
    (is (= "first" (:buf (keys->state st [:up :up]))) "stops at the oldest")
    (is (= "draft" (:buf (keys->state st [:down]))) "down returns to the draft")))

(deftest history-file-round-trip
  (let [dir (fs/create-temp-dir {:prefix "bba-test"})
        file (str (fs/path dir "h" "history"))]
    (try
      (let [h (-> [] (tui/add-history file "one") (tui/add-history file "two\nlines")
                  (tui/add-history file "two\nlines") (tui/add-history file "  "))]
        (is (= ["one" "two\nlines"] h) "no blank entries, no repeats")
        (is (= h (tui/load-history file)) "multi-line entries survive the file"))
      (finally (fs/delete-tree dir)))))

(deftest press-to-key-mapping
  (is (= [:insert "h"] (tui/press->key (msg/key-press "h"))))
  (is (= :enter (tui/press->key (msg/key-press :enter))))
  (is (= [:insert " "] (tui/press->key (msg/key-press :space))))
  (is (= :ctrl-c (tui/press->key (msg/key-press "c" :ctrl true))))
  (is (= :kill-word (tui/press->key (msg/key-press "w" :ctrl true))))
  (is (= :home (tui/press->key (msg/key-press "a" :ctrl true))))
  (is (= :newline (tui/press->key (msg/key-press :enter :alt true))) "Alt+Enter is a new line")
  (is (= :newline (tui/press->key (msg/key-press "\r" :alt true))) "ESC CR, as charm reads a real Alt+Enter")
  (is (nil? (tui/press->key (msg/window-size 80 24))) "non-key messages are ignored")
  (is (nil? (tui/press->key (msg/key-press :f5))) "unbound keys are ignored"))

(deftest view-shows-prompt-buffer-and-caret
  (let [v (tui/view-str "› " "› " {:buf "ab" :pos 2})]
    (is (str/starts-with? v "› "))
    (is (str/includes? v "ab"))
    (is (str/includes? v "\u001b[7m") "the caret is reverse video"))
  (testing "continuation lines get a blank prefix as wide as the plain prompt"
    (let [v (tui/view-str "› " "› " {:buf "a\nb" :pos 3})]
      (is (str/includes? v (str "\n" "  " "b")) "two spaces before the continuation")))
  (testing "a styled prompt must not widen the continuation indent"
    (let [v (tui/view-str "› " "\u001b[36m› \u001b[0m" {:buf "a\nb" :pos 3})]
      (is (str/includes? v (str "\n" "  " "b")) "indent counts the plain prompt, not its escapes"))))

;; ---------------------------------------------------------------- tool view

(deftest tool-calls-show-code
  (is (= "● execute (project-brief)" (toolview/call-str "execute_form" {:code "(project-brief)"} 80))
      "one-line code stays on the label line")
  (is (= "● execute (->> xs (map inc))"
         (toolview/call-str "execute_form" {:code "(->> xs\n     (map inc))"} 80))
      "multi-line code is collapsed to one line")
  (is (= "● read_skill {\"name\":\"deploy\"}" (toolview/call-str "read_skill" {:name "deploy"} 80))))

(deftest tool-results-show-status
  (is (= "  ⎿ ✓ => 42  2 chars"
         (toolview/result-str "execute_form" (pr-str {:status :accepted :value "42"}) false 80)))
  (is (str/starts-with? (toolview/result-str "develop_form" (pr-str {:status :accepted :defined ["f" "g"]}) false 80)
                        "  ⎿ ✓ defined f g"))
  (is (= "  ⎿ ✗ IllegalArgumentException: bad"
         (toolview/result-str "execute_form" (pr-str {:status :error :class "java.lang.IllegalArgumentException"
                                                      :message "bad"}) true 80))
      "an error shows the short class name and the message")
  (is (= "  ⎿ 5 chars" (toolview/result-str "read_skill" "hello" false 80)))
  (is (= "  ⎿ ✗ rejected (redefined): foo"
         (toolview/result-str "develop_form" (pr-str {:status :rejected :reason :redefined :vars ["foo"]}) true 80))
      "a rejected change is a failure, not a ✓")
  (is (str/starts-with? (toolview/result-str "execute_form" (pr-str {:status :accepted :value (pr-str "a\nb")}) false 80)
                        "  ⎿ ✓ => a b")
      "a string value shows its text, not its escapes"))

(deftest a-call-label-links-to-its-detail
  (try (ui/enable! true)
       (is (str/includes? (toolview/call-str "execute_form" {:code "(f)"} 80 "file:///tmp/x.md")
                          "\u001b]8;;file:///tmp/x.md\u001b\\"))
       (finally (ui/enable! false)))
  (is (= "● execute (f)" (toolview/call-str "execute_form" {:code "(f)"} 80 "file:///tmp/x.md"))
      "with colour off there is no link"))

(deftest the-detail-has-the-full-code-and-result
  (let [d (toolview/detail-md "execute_form" {:code "(->> xs\n     (map inc))"}
                              (pr-str {:status :accepted :value (pr-str "line one\nline two")}))]
    (is (str/includes? d "```clojure\n(->> xs\n     (map inc))\n```") "the code keeps its lines")
    (is (str/includes? d "line one\nline two") "a string value is shown as text")
    (is (str/includes? d "**Result** (accepted)"))))

(deftest ctrl-o-expands-only-at-an-empty-prompt
  (is (= :expand (tui/press->key (msg/key-press "o" :ctrl true))))
  (is (= [:expand] (:done (tui/handle-key (tui/new-state []) :expand))))
  (is (nil? (:done (tui/handle-key (assoc (tui/new-state []) :buf "x" :pos 1) :expand)))
      "with text in the line, Ctrl-O does nothing"))

(deftest the-pager-scrolls-and-closes
  (let [st {:vp (charm.components.viewport/viewport (str/join "\n" (range 50)) :height 5)}
        [st2 cmd] (tui/pager-update st (msg/key-press :down))]
    (is (= 1 (get-in st2 [:vp :y-offset])) "down scrolls one line")
    (is (nil? cmd))
    (doseq [k [(msg/key-press "q") (msg/key-press :escape) (msg/key-press "o" :ctrl true)]]
      (is (some? (second (tui/pager-update st k))) (str "closes on " (pr-str (:key k)))))
    (is (= 9 (get-in (first (tui/pager-update st (msg/window-size 80 10))) [:vp :height]))
        "a resize leaves one row for the footer")))

(deftest one-line-code-fits-beside-its-label
  (let [s (toolview/call-str "execute_form" {:code (apply str (repeat 100 "x"))} 40)]
    (is (= 40 (count s)) "the label and the code fill the width and no more")))

(deftest stream-printer-redraws-a-table-at-its-end
  (let [out (with-out-str
              (let [p (#'main/stream-printer {:md (bba.md/context {:width 40 :render true :palette {}})})]
                ((:text p) "| a | b |\n|---|---|\n| 1 | 2 |\nafter\n")))]
    (is (str/includes? out "\r\u001b[3A\u001b[J┌") "it moves up over the three raw rows and draws the box")
    (is (str/ends-with? out "└───┴───┘\nafter\n"))))

;; ---------------------------------------------------------------- streaming folds

(deftest stream-printer-renders-wrapped-lines
  (let [out (with-out-str
              (let [p (#'main/stream-printer {:md (bba.md/context {:width 20 :render true})})]
                ((:text p) "a **bold** word in a line ")  ; 26 cells: two rows at width 20
                ((:text p) "that wraps\n")
                ((:text p) "next")
                ((:flush p))))]
    (is (str/includes? out "a **bold** word in a line ") "chunks stream on one line")
    (is (str/includes? out "\r\u001b[1A\u001b[J") "the redraw moves up over the second raw row")
    (is (not (str/includes? (subs out (str/index-of out "\u001b[J")) "**")) "the redraw has no ** marks")
    (is (str/ends-with? out "next\n") "the last line is ended by :flush")))

(deftest openai-chunks-fold-into-a-reply
  (let [seen (atom [])
        chunks [{:choices [{:delta {:content "Hel"}}]}
                {:choices [{:delta {:content "lo"}}]}
                {:choices [{:delta {:tool_calls [{:index 0 :id "c1" :function {:name "bash" :arguments "{\"comm"}}]}}]}
                {:choices [{:delta {:tool_calls [{:index 0 :function {:arguments "and\":\"ls\"}"}}]}}]}]
        acc (reduce #(openai/chunk-step %1 %2 (fn [s] (swap! seen conj s))) openai/stream-init chunks)
        reply (openai/->reply (openai/stream-body acc))]
    (is (= ["Hel" "lo"] @seen) "text goes to on-text as it arrives")
    (is (= [{:type "text" :text "Hello"} {:type "tool_use" :id "c1" :name "bash" :input {:command "ls"}}]
           (:content reply))))
  (is (thrown-with-msg? Exception #"API error: overloaded"
                        (openai/chunk-step openai/stream-init {:error {:message "overloaded"}} nil))))

(deftest anthropic-events-fold-into-a-message
  (let [seen (atom [])
        events [{:type "message_start" :message {:id "m" :content []}}
                {:type "content_block_start" :index 0 :content_block {:type "text" :text ""}}
                {:type "content_block_delta" :index 0 :delta {:type "text_delta" :text "Hi"}}
                {:type "content_block_start" :index 1 :content_block {:type "tool_use" :id "t1" :name "read" :input {}}}
                {:type "content_block_delta" :index 1 :delta {:type "input_json_delta" :partial_json "{\"path\":"}}
                {:type "content_block_delta" :index 1 :delta {:type "input_json_delta" :partial_json "\"a.txt\"}"}}
                {:type "message_delta" :delta {:stop_reason "tool_use"}}
                {:type "ping"}]
        msg (provider/anthropic-message (reduce #(provider/anthropic-step %1 %2 (fn [s] (swap! seen conj s)))
                                                {:blocks (sorted-map)} events))]
    (is (= ["Hi"] @seen))
    (is (= "tool_use" (:stop_reason msg)))
    (is (= [{:type "text" :text "Hi"} {:type "tool_use" :id "t1" :name "read" :input {:path "a.txt"}}]
           (:content msg)))))

(defn- sse-server
  "Local server that answers with `events` as server-sent events. Returns [stop base-url requests]."
  [events]
  (let [reqs (atom [])
        stop (server/run-server
              (fn [req] (swap! reqs conj (json/parse-string (slurp (:body req)) true))
                {:status 200 :headers {"content-type" "text/event-stream"}
                 :body (str (str/join (map #(str ": keep-alive\n\ndata: " (json/generate-string %) "\n\n") events))
                            "data: [DONE]\n\n")})
              {:port 0 :legacy-return-value? false})]
    [stop (str "http://127.0.0.1:" (server/server-port stop)) reqs]))

(deftest chat-completions-stream-over-http
  (let [[stop base reqs] (sse-server [{:choices [{:delta {:content "str"}}]} {:choices [{:delta {:content "eam"}}]}])
        seen (atom [])]
    (try
      (let [r ((provider/ollama {"OLLAMA_HOST" base}) {:messages [] :system "s" :on-text #(swap! seen conj %)})]
        (is (= ["str" "eam"] @seen))
        (is (= "stream" (-> r :content first :text)))
        (is (true? (:stream (first @reqs))) "asks the server to stream"))
      (finally (server/server-stop! stop)))))

;; ---------------------------------------------------------------- resume

(def u (core/user-message "q"))

(def answer {:role "assistant" :content [{:type "text" :text "a"}]})

(def tool-call {:role "assistant" :content [{:type "tool_use" :id "t" :name "bash" :input {}}]})

(def result {:role "user" :content [{:type "tool_result" :tool_use_id "t" :content "ok"}]})

(deftest replayable-drops-incomplete-turns
  (is (= [u tool-call result answer] (core/replayable [u tool-call result answer])))
  (is (= [u answer] (core/replayable [u answer u tool-call])) "a cancelled last turn is dropped")
  (is (= [u answer] (core/replayable [u tool-call u answer])) "a cancelled turn in the middle is dropped")
  (is (= [] (core/replayable [result answer])) "messages without a user turn are skipped"))

(deftest continue-flag-resumes-the-latest-session
  (let [dir (str (fs/create-temp-dir {:prefix "bba-test"}))
        reqs (atom [])
        fake (fn [req] (swap! reqs conj req) {:role "assistant" :content [{:type "text" :text "ok"}]})
        run #(binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)]
               (ext/set-provider! fake)
               (main/run {:args % :cwd dir :env {"BBA_HOME" (str dir "/home")} :in nil}))]
    (try
      (is (= 0 (run ["-p" "first"])))
      (is (= 0 (run ["-c" "-p" "second"])))
      (is (= ["first" "ok" "second"]
             (map #(-> % :content first :text) (:messages (last @reqs))))
          "the second run sees the first exchange")
      (is (= 1 (count (fs/glob (fs/path dir ".bba" "sessions") "*.jsonl"))) "-c appends to the same file")
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------- cancel

(deftest interrupt-stops-a-shell-command
  (let [out (promise)
        t0 (System/currentTimeMillis)
        th (Thread. #(deliver out (try (sys/sh "sleep 20")
                                       (catch InterruptedException _ ::interrupted))))]
    (.start th)
    (Thread/sleep 300)
    (.interrupt th)
    (is (= ::interrupted (deref out 5000 ::timeout)) "the interrupt reaches the caller")
    (is (< (- (System/currentTimeMillis) t0) 5000))))
