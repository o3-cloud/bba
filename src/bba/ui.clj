(ns bba.ui
  "Tiny ANSI TUI helpers: SGR colours, a spinner and a line-clearing helper.
  Plain text unless main calls (enable! true) for a terminal; NO_COLOR keeps it
  plain. Every colour helper is identity when colour is off, so a caller can
  style unconditionally; `enabled?` is for the few things that must not be
  emitted at all, such as an OSC 8 hyperlink.")

(defonce on (atom false))  ; main turns this on for a terminal

(defn enable! [b] (reset! on (boolean (and b (not (System/getenv "NO_COLOR"))))))

(defn enabled? [] @on)

(defn- wrap [code s] (if @on (str "\033[" code "m" s "\033[0m") s))
(def bold (partial wrap "1"))
(def dim (partial wrap "2"))
(def italic (partial wrap "3"))
(def underline (partial wrap "4"))
(def strike (partial wrap "9"))
(def red (partial wrap "31"))
(def green (partial wrap "32"))
(def yellow (partial wrap "33"))
(def blue (partial wrap "34"))
(def magenta (partial wrap "35"))
(def cyan (partial wrap "36"))
(def grey (partial wrap "90"))

(defn clear-line [] (when @on (print "\r\033[2K") (flush)))
(defn clear-screen [] (when @on (print "\033[2J\033[H") (flush)))

;; ---------------------------------------------------------------- spinner

(def frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defonce state (atom nil))
(defonce worker (atom nil))
(def ^:private lock (Object.))

(defn- draw! []
  (let [{:keys [i label t0]} @state
        secs (format "%.0fs" (/ (- (System/currentTimeMillis) t0) 1000.0))]
    (print (str "\r" (yellow (nth frames i)) " " label " " (dim secs) "\033[K"))
    (flush)))

(defn start! [label]
  (when @on
    (locking lock
      (clear-line)
      (reset! state {:i 0 :label label :t0 (System/currentTimeMillis)})
      (draw!)
      (when-not (and @worker (not (.isDone ^java.util.concurrent.Future @worker)))
        (reset! worker
                (future
                  (loop []
                    (Thread/sleep 100)
                    (when @state
                      (locking lock
                        (swap! state update :i #(mod (inc %) (count frames)))
                        (draw!))
                      (recur)))))))))

(defn stop! []
  (locking lock (when @state (reset! state nil) (clear-line))))

(defn log!
  "Print a progress line, pausing and redrawing the spinner around it."
  [s]
  (locking lock
    (when @state (clear-line))
    (println s)
    (when @state (draw!))))
