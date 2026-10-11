(ns bba.codex-auth
  "Read the ChatGPT subscription credentials that the Codex CLI writes.

  A ChatGPT plan covers the Codex endpoint but not the metered API, and the two
  use different tokens. bba reads the Codex CLI's own login instead of asking for
  an API key.

  This namespace is deliberately read-only: it never refreshes and never writes.
  Refreshing would rotate the single-use refresh token and sign the Codex CLI out,
  so bba only reads and says plainly when the token needs renewing."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn auth-file
  "Path of the Codex CLI auth file: CODEX_HOME/auth.json, else ~/.codex/auth.json."
  [env]
  (let [home (or (not-empty (get env "CODEX_HOME"))
                 (str (System/getProperty "user.home") "/.codex"))]
    (str home "/auth.json")))

(defn- b64url->str [s]
  (let [p (-> s (str/replace "-" "+") (str/replace "_" "/"))
        p (str p (apply str (repeat (mod (- 4 (mod (count p) 4)) 4) "=")))]
    (String. (.decode (java.util.Base64/getDecoder) p) "UTF-8")))

(defn claims
  "The JWT payload of `token`, or nil when it is not a readable JWT.
  Keys stay strings: the namespaced claim \"https://api.openai.com/auth\" is not a
  valid Clojure keyword."
  [token]
  (try
    (let [payload (second (str/split (str token) #"\."))]
      (json/parse-string (b64url->str (str payload))))
    (catch Exception _ nil)))

(defn expiry
  "The `exp` of `token`, in epoch seconds. nil when it is not a readable JWT."
  [token]
  (some-> (get (claims token) "exp") long))

(defn account-id
  "The ChatGPT account id of `token`, or nil."
  [token]
  (get-in (claims token) ["https://api.openai.com/auth" "chatgpt_account_id"]))

(defn plan
  "The ChatGPT plan of `token` (for example \"plus\"), or nil."
  [token]
  (get-in (claims token) ["https://api.openai.com/auth" "chatgpt_plan_type"]))

(defn expired?
  "True when `token` is a JWT that is past its `exp`; false when it is still valid;
  nil when that cannot be told (a token that is not a readable JWT)."
  [token]
  (when-let [e (expiry token)] (< e (quot (System/currentTimeMillis) 1000))))

(defn access-token
  "The ChatGPT access token: CODEX_ACCESS_TOKEN when set, else `tokens.access_token`
  from the Codex CLI auth file. nil when neither is there."
  [env]
  (or (not-empty (get env "CODEX_ACCESS_TOKEN"))
      (try
        (let [f (java.io.File. (auth-file env))]
          (when (.isFile f)
            (not-empty (get-in (json/parse-string (slurp f) true) [:tokens :access_token]))))
        (catch Exception _ nil))))