;; Example extension: block bash commands that contain "rm -rf".
;; Example only, not a security boundary: plain text match, so "rm -fr" gets past it.
;; Copy to .bba/extensions/ (project) or ~/.bba/extensions/ (user) to enable.
(ns bba.extensions.block-rm-rf
  (:require [bba.ext :as ext]
            [clojure.string :as str]))

(ext/on! :tool-call
         (fn [{:keys [name input]} _ctx]
           (when (and (= name "bash") (str/includes? (str (:command input)) "rm -rf"))
             {:block true :reason "rm -rf is not allowed"})))
