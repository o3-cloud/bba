(ns bba.tools
  "The four built-in tools: read, write, edit, bash."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [bba.ext :as ext]))

(def max-lines 2000)
(def max-bytes (* 100 1024))
(def default-timeout-s 120)

(defn- resolve-path [ctx path]
  (str (if (fs/absolute? path) path (fs/path (:cwd ctx) path))))

(defn- err [msg] {:content msg :is-error true})

(defn read-tool [{:keys [path offset limit]} ctx]
  (let [f (resolve-path ctx path)]
    (cond
      (not (fs/exists? f)) (err (str "file not found: " path))
      (fs/directory? f) (err (str "is a directory: " path))
      :else
      (let [bs (fs/read-all-bytes f)]
        (if (some zero? (take 8192 bs))
          (err (str "binary file: " path))
          (let [lines (str/split-lines (String. ^bytes bs "UTF-8"))
                start (max 0 (dec (or offset 1)))
                n (min (or limit max-lines) max-lines)
                picked (take n (drop start lines))
                text (str/join "\n" picked)
                cut? (or (< (+ start n) (count lines)) (> (count text) max-bytes))
                text (subs text 0 (min (count text) max-bytes))]
            (cond-> text
              cut? (str "\n[truncated: file has " (count lines) " lines; use offset/limit]"))))))))

(defn write-tool [{:keys [path content]} ctx]
  (let [f (resolve-path ctx path)]
    (some-> (fs/parent f) fs/create-dirs)
    (spit f content)
    (str "wrote " (count content) " chars to " path)))

(defn edit-tool [{:keys [path old new]} ctx]
  (let [f (resolve-path ctx path)]
    (if-not (fs/exists? f)
      (err (str "file not found: " path))
      (let [text (slurp f)
            n (count (re-seq (re-pattern (java.util.regex.Pattern/quote old)) text))]
        (case n
          0 (err (str "old text not found in " path))
          1 (do (spit f (str/replace-first text old new)) (str "edited " path))
          (err (str "old text is not unique in " path " (" n " matches)")))))))

(defn bash-tool [{:keys [command timeout]} ctx]
  (let [ms (* 1000 (or timeout default-timeout-s))
        proc (p/process {:dir (:cwd ctx) :out :string :err :out :in ""} "bash" "-c" command)
        res (deref proc ms ::timeout)]
    (if (= res ::timeout)
      (do (p/destroy-tree proc)
          (err (str (:out @proc) "\n[timed out after " (/ ms 1000) " s]")))
      (let [{:keys [out exit]} res
            text (str out (when-not (zero? exit) (str "\n[exit " exit "]")))]
        (if (zero? exit) text (err text))))))

(defn- schema [props required]
  {:type "object" :properties props :required required})

(defn register-builtins! []
  (binding [ext/*source* :builtin]
    (ext/register-tool!
     {:name "read" :handler read-tool
      :description "Read a text file. Optional 1-based line offset and line limit."
      :input-schema (schema {:path {:type "string"} :offset {:type "integer"} :limit {:type "integer"}} ["path"])})
    (ext/register-tool!
     {:name "write" :handler write-tool
      :description "Write content to a file, creating parent folders. Overwrites."
      :input-schema (schema {:path {:type "string"} :content {:type "string"}} ["path" "content"])})
    (ext/register-tool!
     {:name "edit" :handler edit-tool
      :description "Replace the exact text `old` with `new` in a file. `old` must appear exactly once."
      :input-schema (schema {:path {:type "string"} :old {:type "string"} :new {:type "string"}} ["path" "old" "new"])})
    (ext/register-tool!
     {:name "bash" :handler bash-tool
      :description "Run a bash command in the working folder. Returns stdout+stderr. Optional timeout in seconds."
      :input-schema (schema {:command {:type "string"} :timeout {:type "integer"}} ["command"])})))
