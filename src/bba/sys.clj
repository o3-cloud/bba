(ns bba.sys
  "Helpers the world namespace gets as `sys`, so execute_form can work with the machine.
  Files need no helper: slurp, spit and babashka.fs work as usual."
  (:require [babashka.process :as p]))

(def world-requires
  "The aliases every world namespace gets. replay! requires them on each rebuild, so
  they survive restores, and clj-kondo sees the same list."
  '[[clojure.string :as str] [clojure.set :as set] [babashka.fs :as fs] [bba.sys :as sys]
    [babashka.http-client :as http] [cheshire.core :as json] [clojure.data.csv :as csv]
    [clj-yaml.core :as yaml] [clojure.java.io :as io] [clojure.edn :as edn]
    [clojure.walk :as walk] [clojure.pprint :as pp] [babashka.process :as proc]])

(defn sh
  "Run a bash command in the working folder. Returns stdout+stderr as a string, with
  \"[exit N]\" appended when N is not 0. Ctrl-C, the world timeout or `timeout-s` kill the
  whole process tree."
  ([command] (sh command {}))
  ([command {:keys [dir timeout-s] :or {timeout-s 120}}]
   (let [proc (p/process {:dir (or dir (System/getProperty "user.dir")) :out :string :err :out :in ""}
                         "bash" "-c" command)
         res (try (deref proc (* 1000 timeout-s) ::timeout)
                  (catch InterruptedException e (p/destroy-tree proc) (throw e)))]
     (if (= res ::timeout)
       (do (p/destroy-tree proc)
           (str (:out @proc) "\n[timed out after " timeout-s " s]"))
       (let [{:keys [out exit]} res]
         (str out (when-not (zero? exit) (str "\n[exit " exit "]"))))))))
