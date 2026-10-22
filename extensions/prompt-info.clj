(ns bba.extensions.prompt-info
  "Built-in extension: where the next message goes, shown under the input.

  Three facts: the working folder, the world, and the target. The world is the
  set of functions that the model can call. The target is the provider and the
  model that the next message goes to. Together they answer the question that a
  session asks most often: what am I talking to, where, and with which functions.

  Every part comes from a :prompt-footer hook. main asks core/prompt-footer for
  them at each frame, so a /provider, /model or /world change shows at the next
  prompt with no restart. The hooks read the live ctx, so this file keeps no
  state of its own. core joins the text of each hook with a dot, so these parts
  and the context part of context.clj share one row.

  The folder part is the full path, not a shortened path. core cuts the finished
  row to the terminal width, so a narrow terminal loses the end of the row."
  (:require [clojure.string :as str]
            [bba.ext :as ext]
            [bba.provider :as provider]))

(defn cwd-str
  "The full path of the working folder, or nil when the ctx has none."
  [{:keys [cwd]}]
  (when-not (str/blank? (str cwd)) (str cwd)))

(defn world-str
  "The world that the ctx names, or nil when it names none."
  [{:keys [world]}]
  (when-not (str/blank? (str world)) (str world)))

(defn target-str
  "The provider and the model that the next message uses."
  [{:keys [env]}]
  (str (provider/provider-name env) " · " (provider/model-name env)))

(def hook-row-top 0)

(def hook-row-default 1)

(defn- hook
  "A :prompt-footer hook that draws `s` at `order` on `row`. A nil or blank `s`
  draws nothing. A failure warns and draws nothing, so the other rows still show."
  ([order s] (hook order s hook-row-default))
  ([order s row]
   (try
     (when-not (str/blank? (str s)) {:row row :order order :text (str s)})
     (catch Exception e
       (ext/warn "prompt-footer hook failed: " (ex-message e))
       nil))))

(defn cwd-footer
  "The :prompt-footer hook: the full working folder. Top row, order 5, so the
  folder starts the top row and a narrow terminal cuts the end of the row below,
  not the folder."
  [_ ctx]
  (hook 5 (cwd-str ctx) hook-row-top))

(defn world-footer
  "The :prompt-footer hook: the world that the session runs. Top row, order 7, so
  it comes after the folder. A world name is short, and it changes the set of
  functions that the model can call, so it earns a place on the top row."
  [_ ctx]
  (hook 7 (world-str ctx) hook-row-top))

(defn footer
  "The :prompt-footer hook: the target of the next message. Row 1, order 10, so it
  comes below the folder and the world, in front of the context part."
  [_ ctx]
  (hook 10 (target-str ctx) hook-row-default))

(ext/on! :prompt-footer cwd-footer)
(ext/on! :prompt-footer world-footer)
(ext/on! :prompt-footer footer)
