;; Example extension: block world forms that contain "rm -rf" (for example (sys/sh "rm -rf ...")).
;; Example only, not a security boundary: plain text match, so "rm -fr" gets past it.
;; Copy to .bba/extensions/ (project) or ~/.bba/extensions/ (user) to enable.
(ns bba.extensions.block-rm-rf
  (:require [bba.ext :as ext]
            [clojure.string :as str]))

(ext/on! :tool-call
         (fn [{:keys [name input]} _ctx]
           (when (and (#{"execute_form" "develop_form"} name) (str/includes? (str (:code input)) "rm -rf"))
             {:block true :reason "rm -rf is not allowed"})))
