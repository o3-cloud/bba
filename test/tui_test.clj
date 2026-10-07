(ns tui-test
  "Line editor, layout, streaming folds, session resume and cancel. No terminal needed."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as server]
            [bba.core :as core]
            [bba.ext :as ext]
            [bba.main :as main]
            [bba.openai :as openai]
            [bba.provider :as provider]
            [bba.tools :as tools]
            [bba.tui :as tui]))

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

;; ---------------------------------------------------------------- layout

(deftest cursor-position-with-wrap-and-newlines
  (is (= [0 5] (tui/cursor-at "› abc" 80)))
  (is (= [1 0] (tui/cursor-at "abcd" 4)) "a full row moves the cursor to the next row")
  (is (= [1 1] (tui/cursor-at "abcde" 4)))
  (is (= [2 2] (tui/cursor-at "ab\ncd\nxy" 80)))
  (is (= "› a\n  b" (tui/display "› " "a\nb")))
  (is (= 7 (tui/display-index "› " "a\nb" 3)) "index after b counts the continuation prefix")
  (is (= 2 (tui/display-index "› " "" 0)) "empty buffer: cursor after the prompt"))

;; ---------------------------------------------------------------- streaming folds

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
(def use {:role "assistant" :content [{:type "tool_use" :id "t" :name "bash" :input {}}]})
(def result {:role "user" :content [{:type "tool_result" :tool_use_id "t" :content "ok"}]})

(deftest replayable-drops-incomplete-turns
  (is (= [u use result answer] (core/replayable [u use result answer])))
  (is (= [u answer] (core/replayable [u answer u use])) "a cancelled last turn is dropped")
  (is (= [u answer] (core/replayable [u use u answer])) "a cancelled turn in the middle is dropped")
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

(deftest interrupt-stops-a-bash-tool-call
  (tools/register-builtins!)
  (let [out (promise)
        t0 (System/currentTimeMillis)
        th (Thread. #(deliver out (try (core/run-tool {:id "x" :name "bash" :input {:command "sleep 20"}}
                                                      {:cwd (str (fs/cwd))})
                                       (catch InterruptedException _ ::interrupted))))]
    (.start th)
    (Thread/sleep 300)
    (.interrupt th)
    (is (= ::interrupted (deref out 5000 ::timeout)) "the interrupt reaches the caller, not a tool_result")
    (is (< (- (System/currentTimeMillis) t0) 5000))))
