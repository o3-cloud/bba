;; Example extension: a tool that reverses text.
;; Copy to .bba/extensions/ (project) or ~/.bba/extensions/ (user) to enable.
(ns bba.extensions.reverse
  (:require [bba.ext :as ext]
            [clojure.string :as str]))

(ext/register-tool!
 {:name "reverse"
  :description "Reverse a string."
  :input-schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
  :handler (fn [{:keys [text]} _ctx] (str/reverse text))})
