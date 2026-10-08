(ns bba.lint
  "clj-kondo checks for world forms, through the clj-kondo babashka pod. The pod is
  loaded on first use (downloaded once to ~/.babashka/pods). When it cannot load,
  linting is skipped with one warning and every attempt goes on unlinted."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [clojure.string :as str]
            [bba.ext :as ext]
            [bba.sys :as sys]))

(def pod-version "2025.06.05")

;; nil = not tried yet, false = unavailable, else the clj-kondo run! fn
(defonce ^:private runner (atom nil))

(defn- run-fn []
  (when (nil? @runner)
    (reset! runner
            (try (pods/load-pod 'clj-kondo/clj-kondo pod-version)
                 (requiring-resolve 'pod.borkdude.clj-kondo/run!)
                 (catch Throwable e
                   (ext/warn "clj-kondo pod unavailable, world forms are not linted: " (or (ex-message e) e))
                   false))))
  @runner)

(def ^:private config
  ;; Forms are snippets in a live namespace: the ns header is ours, and a develop
  ;; form redefines what the context already defines.
  {:output {:canonical-paths true}
   :linters {:namespace-name-mismatch {:level :off}
             :unused-namespace {:level :off}
             :redefined-var {:level :off}}})

(defn header
  "The ns form clj-kondo sees: the world namespace with the aliases replay! requires."
  [ns-name]
  (str "(ns " ns-name " (:require " (str/join " " (map pr-str sys/world-requires)) "))\n"))

(defn lint
  "Lint `code` as it would run in namespace `ns-name` after the forms in `context`
  (source strings, so calls to world functions are checked for arity). Returns
  [{:row :col :level :type :message}] with rows counted inside `code`, or nil when
  clj-kondo is unavailable."
  [ns-name context code]
  (when-let [run! (run-fn)]
    (let [prefix (str (header ns-name) (str/join "\n" context) "\n")
          offset (count (filter #{\newline} prefix))
          f (fs/create-temp-file {:prefix "bba-lint" :suffix ".clj"})]
      (try
        (spit (str f) (str prefix code))
        (->> (:findings (run! {:lint [(str f)] :config config}))
             (filter #(> (:row % 0) offset))
             (mapv #(-> (select-keys % [:row :col :level :type :message])
                        (update :row - offset))))
        (finally (fs/delete-if-exists f))))))

(defn errors [findings] (filterv #(= :error (:level %)) findings))
