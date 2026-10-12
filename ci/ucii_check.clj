(ns ucii-check
  "Checks that ucii.json and the Makefile describe the same UCII interface
  (https://github.com/stackable-specs/ucii, v0.1). `problems` is pure: it takes the
  parsed manifest, the Makefile text and a `resolves?` function, and returns every
  problem as a coded error {:operation :code :message}, not only the first. `-main`
  supplies the real files and resolves an operation by dry-running its target with
  `make -n`, so no operation runs."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def spec-version "ucii/v0.1")

(def standard-ops
  #{"describe" "plan" "build" "test" "verify" "scan" "package" "publish" "promote"
    "provision" "configure" "deploy" "rollback" "status" "observe" "clean"})

(def read-only-ops
  "Standard names the spec defines as read-only (§17)."
  #{"describe" "status" "observe" "verify" "scan"})

(def effect-classes #{"read-only" "local" "external" "destructive"})

(defn tagged-targets
  "The Makefile targets tagged `## [ucii]`, in file order."
  [makefile]
  (map second (re-seq #"(?m)^([A-Za-z0-9_.-]+):[^\n]*##\s*\[ucii\]" makefile)))

(defn phony-targets
  "Every target named on a `.PHONY:` line, with backslash continuations joined. An
  operation that is not phony is skipped when a file or folder has its name: `make
  test` with a test/ folder reports \"up to date\" and exits 0 without running."
  [makefile]
  (->> (re-seq #"(?m)^\.PHONY:(.*)$" (str/replace makefile #"\\\n" " "))
       (mapcat #(str/split (str/trim (second %)) #"\s+"))
       (remove str/blank?)
       set))

(defn- err [op code message] {:operation op :code code :message message})

(defn- param-problems [op makefile params]
  (for [[pname {:strs [type maps]}] params
        :let [var (second (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=<value>" (str maps)))]
        e [(when-not (string? type)
             (err op "parameter-type" (str "parameter " pname " has no type")))
           (when-not var
             (err op "parameter-mapping" (str "parameter " pname " maps to " (pr-str maps) ", not NAME=<value>")))
           (when (and var (not (re-find (re-pattern (str "\\$\\(" var "\\)|\\bexport\\s+" var "\\b")) makefile)))
             (err op "parameter-unused" (str "parameter " pname " maps to " var ", which the Makefile never reads")))]
        :when e]
    e))

(defn- op-problems [op m makefile resolves?]
  (let [{:strs [description standard required invoke effects idempotent parameters]} m]
    (concat
     (remove nil?
             [(when (str/blank? (str description))
                (err op "description-missing" "no description"))
              ;; §9: what a profile requires is marked required, everything else
              ;; optional. This project claims no profile, so nothing is required.
              (when-not (false? required)
                (err op "required-wrong" (str "required is " (pr-str required) ", expected false: no profile is claimed")))
              (when-not (contains? (phony-targets makefile) op)
                (err op "not-phony" (str op " is not in .PHONY, so a file or folder named " op " stops it from running")))
              (when-not (boolean? standard)
                (err op "standard-missing" "standard must be true or false"))
              (when (and (boolean? standard) (not= standard (contains? standard-ops op)))
                (err op "standard-wrong" (if standard
                                           "marked standard, but UCII v0.1 has no such operation"
                                           "is a UCII v0.1 operation, but marked not standard")))
              (when-not (contains? effect-classes effects)
                (err op "effects-invalid" (str "effects " (pr-str effects) " is not one of " (str/join ", " (sort effect-classes)))))
              (when (and (contains? read-only-ops op) (not= "read-only" effects))
                (err op "effects-contradict-name" (str op " is read-only by definition, but advertised as " (pr-str effects))))
              (when-not (boolean? idempotent)
                (err op "idempotent-missing" "idempotent must be true or false"))
              (when-not (= (str "make " op) (re-find #"^make \S+" (str invoke)))
                (err op "invoke-mismatch" (str "invoke is " (pr-str invoke) ", expected it to start with \"make " op "\"")))
              (when-not (resolves? op)
                (err op "not-resolved" (str "make cannot resolve the target " op)))])
     (param-problems op makefile parameters))))

(defn problems
  "Every way `manifest` (parsed ucii.json, string keys) and `makefile` disagree, or
  the manifest breaks UCII v0.1. Empty when the interface checks out."
  [manifest makefile resolves?]
  (let [ops (get manifest "operations")
        tagged (set (tagged-targets makefile))]
    (vec
     (concat
      (remove nil?
              [(when-not (= spec-version (get manifest "spec"))
                 (err nil "spec-version" (str "spec is " (pr-str (get manifest "spec")) ", expected " (pr-str spec-version))))
               (when-not (contains? #{1 2 3} (get manifest "level"))
                 (err nil "level-invalid" (str "level is " (pr-str (get manifest "level")) ", expected 1, 2 or 3")))
               (when-not (= "make" (get-in manifest ["engine" "name"]))
                 (err nil "engine-mismatch" "engine.name must be \"make\""))
               (when-not (and (map? ops) (seq ops))
                 (err nil "operations-missing" "no operations"))
               (when (and (map? ops) (not (contains? ops "describe")))
                 (err "describe" "describe-missing" "discovery needs a describe operation"))])
      (when (map? ops)
        (concat
         (mapcat (fn [[op m]] (op-problems op m makefile resolves?)) ops)
         (for [op (keys ops) :when (not (contains? tagged op))]
           (err op "not-tagged" "advertised, but the Makefile target is not tagged ## [ucii]"))
         (for [t tagged :when (not (contains? ops t))]
           (err t "not-advertised" "tagged ## [ucii] in the Makefile, but missing from ucii.json"))))))))

(defn make-resolves?
  "True when `make -n target` succeeds in `dir`: the target exists and its recipe
  expands. Nothing is run."
  [dir target]
  (zero? (:exit (p/shell {:dir dir :out :string :err :string :continue true}
                         "make" "-n" "--no-print-directory" target))))

(defn -main [& [dir]]
  (let [dir (or dir ".")
        manifest (json/parse-string (slurp (str dir "/ucii.json")))
        found (problems manifest (slurp (str dir "/Makefile")) (partial make-resolves? dir))]
    (if (empty? found)
      (println (str "ucii: ucii.json matches the Makefile (" (count (get manifest "operations")) " operations)"))
      (do (doseq [{:keys [operation code message]} found]
            (println (str "ucii: " (or operation "-") ": " code ": " message)))
          (System/exit 1)))))
