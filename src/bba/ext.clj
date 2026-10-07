(ns bba.ext
  "The extension API. Extension files `(:require [bba.ext :as ext])` and call
  `register-tool!`, `register-command!`, `on!` and `set-provider!`.
  Every registration is tagged with the file that made it (`*source*`), so
  `/reload` can remove exactly the extension entries and keep the built-ins.")

(def ^:dynamic *source*
  "The file being loaded, or :builtin for core registrations."
  :builtin)

(defonce registry (atom {:tools {} :commands {} :hooks {} :provider nil}))

(defn warn
  "Print a `bba:` warning on stderr."
  [& xs]
  (binding [*out* *err*] (println (apply str "bba: " xs))))

(defn- put! [kind label k v]
  (let [prev (get-in @registry [kind k])]
    (when (and prev (not= (:source prev) *source*))
      (warn label " \"" k "\" from " *source* " replaces " (:source prev)))
    (swap! registry assoc-in [kind k] (assoc v :source *source*))))

(defn register-tool!
  "Register a tool. `tool` = {:name :description :input-schema :handler}.
  The handler is (fn [input ctx]) and returns a string or {:content s :is-error bool}."
  [{:keys [name handler] :as tool}]
  (when-not (and (string? name) (fn? handler))
    (throw (ex-info "register-tool! needs a string :name and a :handler fn" {:tool tool})))
  (put! :tools "tool" name (merge {:description "" :input-schema {:type "object" :properties {}}} tool)))

(defn register-command!
  "Register a slash command. The handler is (fn [args-string ctx])."
  [name handler]
  (put! :commands "command" name {:name name :handler handler}))

(defn on!
  "Add an event hook. Events: :session-start, :tool-call.
  A :tool-call hook gets {:name :input} and may return {:block true :reason s}."
  [event handler]
  (swap! registry update-in [:hooks event] (fnil conj []) {:handler handler :source *source*}))

(defn set-provider!
  "Replace the provider: (fn [{:keys [messages system tools]}]) -> assistant message."
  [f]
  (swap! registry assoc :provider {:fn f :source *source*}))

(defn tools [] (vals (:tools @registry)))
(defn tool [name] (get-in @registry [:tools name]))
(defn command [name] (get-in @registry [:commands name]))
(defn commands [] (vals (:commands @registry)))
(defn hooks [event] (get-in @registry [:hooks event]))
(defn provider [] (get-in @registry [:provider :fn]))

(defn emit
  "Run every hook for a non-guard event. Hook errors warn and are ignored (fail-open)."
  [event payload ctx]
  (doseq [{:keys [handler source]} (hooks event)]
    (try (handler payload ctx)
         (catch Exception e (warn "hook " event " in " source " failed: " (ex-message e))))))

(defn- builtin? [v] (= :builtin (:source v)))

(defn reset-extensions!
  "Remove every registration that did not come from core."
  []
  (swap! registry
         (fn [r]
           (-> r
               (update :tools #(into {} (filter (comp builtin? val)) %))
               (update :commands #(into {} (filter (comp builtin? val)) %))
               (update :hooks #(update-vals % (fn [hs] (filterv builtin? hs))))
               (update :provider #(when (builtin? %) %))))))

(defn reset-all!
  "Empty the registry (used by tests)."
  []
  (reset! registry {:tools {} :commands {} :hooks {} :provider nil}))
