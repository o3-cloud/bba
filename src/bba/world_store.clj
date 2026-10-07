(ns bba.world-store
  "The world store on disk (.bba/world/): adapter, immutable revisions, the CURRENT
  pointer, an advisory lock and the ops log. Pure file I/O; no evaluation here."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [bba.ext :as ext]))

(def default-adapter
  {:ns 'world :invariants [] :goals [] :timeout-ms 30000 :max-result-chars 4000})

(def adapter-template
  (str ";; bba world adapter. Read as EDN data; the forms below are evaluated in the world namespace.\n"
       ";; :invariants must hold after every change (a failure rejects the change).\n"
       ";; :goals are reported as met / unmet and never reject a change.\n"
       ";; Example: {:name \"reverse works\" :form (= \"cba\" (reverse-string \"abc\"))}\n"
       "{:ns world\n :invariants []\n :goals []\n :timeout-ms 30000\n :max-result-chars 4000}\n"))

(defn world-dir [cwd] (str (fs/path cwd ".bba" "world")))
(defn- f [dir & parts] (str (apply fs/path dir parts)))

(defn ensure-dir!
  "Create the store folders, its .gitignore and a default adapter. Returns true when
  the adapter was created now."
  [dir]
  (fs/create-dirs (f dir "revisions"))
  (let [gi (f dir ".gitignore")]
    (when-not (fs/exists? gi) (spit gi "revisions/\nCURRENT\nLOCK\nops.jsonl\n*.tmp\n")))
  (let [a (f dir "world.edn")]
    (when-not (fs/exists? a) (spit a adapter-template) true)))

(defn read-adapter
  "Read world.edn as data (clojure.edn: no reader eval). Missing keys get defaults."
  [dir]
  (let [a (edn/read-string (slurp (f dir "world.edn")))]
    (when-not (map? a) (throw (ex-info "world.edn must be a map" {})))
    (let [a (merge default-adapter a) n (:ns a)]
      ;; the world namespace is removed and rebuilt on every restore: never a harness or library ns
      (when-not (and (simple-symbol? n)
                     (not (re-find #"^(clojure|bba|babashka|sci|cheshire|rewrite-clj)(\.|$)|^user$" (str n))))
        (throw (ex-info (str ":ns " (pr-str n) " is not allowed; use a new name such as world") {})))
      a)))

;; ---------------------------------------------------------------- atomic writes

(defn- atomic-spit!
  "Write `s` to a temp file next to `target`, then move it into place atomically."
  [target s]
  (let [tmp (str (fs/path (fs/parent target) (str "." (fs/file-name target) ".tmp")))]
    (spit tmp s)
    (fs/move tmp target {:atomic-move true :replace-existing true})))

(defn delete-tmp!
  "Delete leftover temp files from an interrupted write. Returns their names."
  [dir]
  (let [tmps (concat (fs/glob dir ".*.tmp") (fs/glob (f dir "revisions") ".*.tmp"))]
    (doseq [t tmps] (fs/delete-if-exists t))
    (mapv (comp str fs/file-name) tmps)))

;; ---------------------------------------------------------------- revisions

(defn- rev-file [dir n] (f dir "revisions" (format "%06d.edn" n)))

(defn rev-numbers
  "Every revision number on disk, sorted."
  [dir]
  (->> (fs/glob (f dir "revisions") "*.edn")
       (keep #(some-> (re-matches #"(\d+)\.edn" (str (fs/file-name %))) second parse-long))
       sort vec))

(defn read-rev [dir n]
  (let [file (rev-file dir n)]
    (when (fs/exists? file) (edn/read-string (slurp file)))))

(defn current
  "The revision number in CURRENT, or nil when it is missing or corrupt."
  [dir]
  (let [c (f dir "CURRENT")]
    (when (fs/exists? c) (some-> (slurp c) str/trim parse-long))))

(defn next-rev
  "1 + the highest revision on disk. Orphans above CURRENT are never reused."
  [dir]
  (inc (or (last (rev-numbers dir)) 0)))

(defn publish!
  "Write revision `rev` (a map with :rev), then move CURRENT to it. The revision file is
  complete before CURRENT changes, so a crash leaves CURRENT on the last complete revision."
  [dir {:keys [rev] :as revision}]
  (let [file (rev-file dir rev)]
    (when (fs/exists? file) (throw (ex-info (str "revision " rev " already exists") {})))
    (atomic-spit! file (binding [*print-length* nil *print-level* nil] (pr-str revision)))
    (atomic-spit! (f dir "CURRENT") (format "%06d\n" rev))
    rev))

;; ---------------------------------------------------------------- lock

(defn- own-pid []
  (str/trim (:out @(p/process ["sh" "-c" "echo $PPID"] {:out :string}))))

(defn- alive? [pid]
  (and (re-matches #"\d+" pid)
       (zero? (:exit @(p/process ["ps" "-p" pid] {:out :string :err :string})))))

(defn lock!
  "Take the advisory LOCK. Throws when another live process holds it; takes over a
  stale lock with a warning. Returns the pid written."
  [dir]
  (let [lock (f dir "LOCK") me (own-pid)]
    (when (fs/exists? lock)
      (let [pid (str/trim (slurp lock))]
        (cond
          (= pid me) nil
          (alive? pid) (throw (ex-info (str "world in use by pid " pid " (lock file " lock ")") {:pid pid}))
          :else (do (ext/warn "taking over stale world lock from pid " pid) (fs/delete-if-exists lock)))))
    (spit lock me)
    me))

(defn unlock!
  "Delete LOCK when this process (`pid`) holds it."
  [dir pid]
  (let [lock (f dir "LOCK")]
    (when (and (fs/exists? lock) (= pid (str/trim (slurp lock))))
      (fs/delete-if-exists lock))))

;; ---------------------------------------------------------------- ops log

(defn log-op!
  "Append one line per attempt to ops.jsonl (and to stderr when BBA_DEBUG is set)."
  [dir entry]
  (let [line (json/generate-string (assoc entry :ts (str (java.time.Instant/now))))]
    (spit (f dir "ops.jsonl") (str line "\n") :append true)
    (when (System/getenv "BBA_DEBUG") (ext/warn "world op " line))))
