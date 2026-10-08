(ns bba.extensions.skills
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [bba.ext :as ext]))

;; Agent Skills (https://agentskills.io/specification) as a bba extension.
;; Progressive disclosure: the tier-1 catalog rides in the activate_skill tool
;; description; the tool returns the full SKILL.md body (tier 2) plus a listing of
;; bundled files (scripts/, references/, assets/ and others) (tier 3, never read eagerly).
;; Scopes: project <cwd>/.agents/skills and <cwd>/.bba/skills are scanned only
;; when trusted (env BBA_SKILLS_TRUST=1 or a .trusted file in the skills root);
;; user ~/.agents/skills, ~/.claude/skills, ~/.bba/skills always; extra roots
;; from BBA_SKILLS_EXTRA (path-separator separated).
;; A skill is a directory containing SKILL.md. Frontmatter: name and description
;; are required; license, compatibility, metadata and allowed-tools optional.
;; name: 1-64 chars of unicode lowercase letters/digits, single hyphens, matching
;; the directory. Discovery is lenient (spec deviations warn, the skill still
;; loads); /skills validate PATH is strict, like skills-ref validate.

(def NL (str (char 10)))
(def QD (str (char 34)))
(def QS (str (char 39)))
(def D3 (apply str (repeat 3 (char 45))))
(def name-re #"^[\p{Ll}\p{Nd}]+(-[\p{Ll}\p{Nd}]+)*$")
(def known-fields
  ["name" "description" "license" "compatibility" "metadata" "allowed-tools"])

(defn valid-name?
  "1-64 chars of unicode lowercase letters and digits, joined by single hyphens."
  [n]
  (boolean (and (string? n) (re-find name-re n) (<= (count n) 64))))

(defn- quoted?
  [t]
  (and (>= (count t) 2)
       (or (= QD (subs t 0 1)) (= QS (subs t 0 1)))
       (= (subs t 0 1) (subs t (dec (count t))))))

(defn- unquote-val
  [t]
  (let [t (str/trim t)]
    (if (quoted? t) (subs t 1 (dec (count t))) t)))

(defn- fm-scalar
  "Quoted values kept whole; unquoted ' #' starts a comment."
  [v]
  (let [t (str/trim v)]
    (when-not (str/blank? t)
      (if (quoted? t)
        (unquote-val t)
        (let [h (str/index-of t " #")]
          (str/trim (if h (subs t 0 h) t)))))))

(defn- split-fm
  "Split SKILL.md text at the --- delimiters."
  [s]
  (let [ls (vec (str/split-lines s))]
    (if (or (< (count ls) 2) (not= D3 (str/trim (first ls))))
      {:fm nil :body s}
      (let [i (first (keep-indexed
                      (fn [k l] (when (and (pos? k) (= D3 (str/trim l))) k))
                      ls))]
        (if i
          {:fm (str/join NL (subvec ls 1 i))
           :body (str/trim (str/join NL (subvec ls (inc i))))}
          {:fm nil :body s})))))

(defn- fm-indent [l] (count (re-find #"^ *" l)))

(defn- fm-first-content
  [ls]
  (some (fn [l]
          (let [t (str/trim l)]
            (when (and (seq t) (not (str/starts-with? t "#"))) l)))
        ls))

(def block-re #"^([|>])([+-]?)\s*(#.*)?$")

(defn- fold
  "YAML folding: adjacent lines join with a space, blank lines become newlines,
  more-indented lines keep their line breaks."
  [ls]
  (let [sb (StringBuilder.)]
    (loop [[l & more :as all] ls prev nil blanks 0]
      (cond
        (empty? all) (str sb)
        (= "" l) (recur more prev (inc blanks))
        :else (do (when prev
                    (.append sb (cond (pos? blanks) (apply str (repeat blanks NL))
                                      (or (str/starts-with? prev " ") (str/starts-with? l " ")) NL
                                      :else " ")))
                  (.append sb l)
                  (recur more l 0))))))

(defn- fm-block
  "A block scalar (| literal or > folded; chomping - strip, + keep, default clip)
  whose lines are indented deeper than ind. Returns [string remaining-lines]."
  [style chomp lines ind]
  (let [[blk more] (split-with #(or (str/blank? %) (> (fm-indent %) ind)) lines)
        bi (or (some->> blk (remove str/blank?) first fm-indent) 0)
        ls (map #(if (str/blank? %) "" (subs % (min bi (count %)))) blk)
        content (vec (reverse (drop-while #{""} (reverse ls))))
        trailing (- (count ls) (count content))
        text (if (= "|" style) (str/join NL content) (fold content))]
    [(cond (empty? content) ""
           (= "-" chomp) text
           (= "+" chomp) (str text (apply str (repeat (inc trailing) NL)))
           :else (str text NL))
     more]))

(defn- seq-item? [l] (re-find #"^ *-( |$)" l))

(defn- fm-seq
  "A block sequence of scalars (- item) at indent ci. Returns [vector remaining-lines]."
  [lines ci]
  (let [[items more] (split-with #(or (str/blank? %) (and (= ci (fm-indent %)) (seq-item? %))) lines)]
    [(vec (keep #(when-not (str/blank? %) (fm-scalar (subs (str/triml %) 1))) items)) more]))

(defn- fm-map
  "Parse an indented frontmatter block into [map remaining-lines]."
  [lines indent]
  (loop [lines lines m {}]
    (if (empty? lines)
      [m []]
      (let [l (first lines)
            ind (fm-indent l)
            t (str/trimr (subs l (min ind (count l))))]
        (cond
          (str/blank? t) (recur (next lines) m)
          (str/starts-with? t "#") (recur (next lines) m)
          (< ind indent) [m lines]
          (> ind indent) (throw (ex-info (str "bad frontmatter indent: " (str/trim t)) {}))
          (not (str/includes? t ":")) (throw (ex-info (str "unsupported frontmatter line: " t) {}))
          :else
          (let [[k v] (str/split t #":" 2)
                k (str/trim k)
                [_ style chomp] (re-find block-re (str/trim v))]
            (cond
              style
              (let [[s more] (fm-block style chomp (next lines) ind)]
                (recur more (assoc m k s)))

              (str/blank? v)
              (let [nx (next lines)
                    c1 (fm-first-content nx)
                    ci (some-> c1 fm-indent)]
                (cond
                  (and ci (>= ci ind) (seq-item? c1))
                  (let [[items more] (fm-seq nx ci)]
                    (recur more (assoc m k items)))

                  (or (nil? ci) (<= ci ind))
                  (recur nx (assoc m k nil))

                  :else
                  (let [[cv more] (fm-map nx ci)]
                    (recur more (assoc m k cv)))))

              :else
              (recur (next lines) (assoc m k (fm-scalar v))))))))))

(defn- fm-yaml
  "Frontmatter YAML subset: scalars (plain, quoted, | and > blocks), block
  sequences of scalars, and nested maps."
  [s]
  (let [ls (vec (str/split-lines s))
        [m more] (fm-map ls 0)]
    (when (some (fn [l] (not (str/blank? (str/trim l)))) more)
      (throw (ex-info "unsupported frontmatter syntax" {})))
    m))

(defn- fm-colon-fix
  "Quote unquoted values containing colons (cross-client YAML leniency)."
  [l]
  (if-let [[_ ind k v] (re-find #"^([ ]*)([^:]{1,100}):[ ]?(.+)$" l)]
    (let [v (str/trim v)]
      (if (and (str/includes? v ":") (not (quoted? v)))
        (str ind k ": " QD v QD)
        l))
    l))

(defn- fm-parse
  [s]
  (let [{:keys [fm body]} (split-fm s)]
    (if (nil? fm)
      {:frontmatter nil :body body :warnings ["no --- frontmatter block found"]}
      (let [r (try {:m (fm-yaml fm)} (catch Exception _ :bad))]
        (if (not= :bad r)
          {:frontmatter (:m r) :body body :warnings []}
          (let [fixed (str/join NL (map fm-colon-fix (str/split-lines fm)))
                r2 (try {:m (fm-yaml fixed)} (catch Exception _ :bad))]
            (if (= :bad r2)
              {:frontmatter nil :body body
               :warnings ["frontmatter unparseable; file treated as body only"]}
              {:frontmatter (:m r2) :body body
               :warnings ["frontmatter parsed after quoting values containing colons"]})))))))

(defn validate-fm
  [m dir-name opts]
  (if (not (map? m))
    {:errors ["frontmatter missing or not a map"] :warnings []}
    (let [strict? (:strict? opts)
          tag (if strict? :e :w)
          g (fn [k] (get m k))
          checks (concat
                  (for [k ["name" "description"]
                        :when (not (string? (g k)))]
                    [:r (str "missing required field: " k)])
                  (let [nm (g "name")]
                    (if (string? nm)
                      (vector
                       (when (str/blank? nm) [:r "name must not be empty"])
                       (when (> (count nm) 64) [tag "name is longer than 64 characters"])
                       (when (and (seq nm) (not (valid-name? nm)))
                         [tag (str "name must be lowercase letters/digits with single hyphens, got: " (pr-str nm))])
                       (when (and dir-name (not= nm dir-name))
                         [tag (str "name does not match directory name " (pr-str dir-name))]))
                      []))
                  (let [d (g "description")]
                    (if (string? d)
                      (vector
                       (when (str/blank? d) [:r "description must not be empty"])
                       (when (> (count d) 1024) [tag "description is longer than 1024 characters"]))
                      []))
                  [(let [c (g "compatibility")]
                     (when (and (some? c) (not (and (string? c) (pos? (count c)) (<= (count c) 500))))
                       [tag "compatibility must be a string of 1-500 characters"]))
                   (let [c (g "license")]
                     (when (and (some? c) (not (string? c))) [tag "license must be a string"]))
                   (let [c (g "allowed-tools")]
                     (when (and (some? c) (not (string? c))) [tag "allowed-tools must be a string"]))
                   (let [c (g "metadata")]
                     (when (and (some? c) (not (and (map? c)
                                                    (every? (fn [e] (and (string? (nth e 0)) (string? (nth e 1)))) c))))
                       [tag "metadata must map string keys to string values"]))
                   (let [u (remove (set known-fields) (keys m))]
                     (when (seq u) [tag (str "unknown frontmatter field(s): " (pr-str (vec (sort u))))]))])
          issues (vec (remove nil? checks))
          req (vec (map peek (filter (fn [i] (= :r (first i))) issues)))
          oth (vec (map peek (filter (fn [i] (not= :r (first i))) issues)))]
      {:errors (if strict? (vec (concat req oth)) req)
       :warnings (if strict? [] oth)})))

(defn validate-file
  "A SKILL.md path -> full result map with :loaded?."
  ([path] (validate-file path nil))
  ([path opts]
   (let [{:keys [frontmatter body] pw :warnings} (fm-parse (slurp path))
         dirp (fs/parent (fs/absolutize path))
         dir-name (str (fs/file-name dirp))
         {:keys [errors warnings]} (validate-fm frontmatter dir-name opts)
         lines (count (str/split-lines (str body)))
         size-warnings (cond-> []
                         (> lines 500) (conj (str "SKILL.md body is " lines " lines; keep it under 500 and move detail to references/"))
                         (> (count (str body)) 20000) (conj "SKILL.md body is over ~5000 tokens; move detail to references/"))
         warnings (concat warnings size-warnings)]
     {:location (str (fs/absolutize path))
      :dir (str dirp)
      :dir-name dir-name
      :name (get frontmatter "name")
      :description (get frontmatter "description")
      :frontmatter (if (map? frontmatter) frontmatter {})
      :body body
      :errors (vec errors)
      :warnings (vec (concat pw warnings))
      :loaded? (and (map? frontmatter) (empty? errors))})))

(defn validate-dir
  "A skill directory (expects dir/SKILL.md) -> full result map."
  ([dir] (validate-dir dir nil))
  ([dir opts]
   (let [sp (str (fs/path dir "SKILL.md"))]
     (if (not (fs/exists? sp))
       {:location sp :dir (str dir)
        :dir-name (str (fs/file-name (fs/absolutize dir)))
        :name nil :description nil :frontmatter {} :body nil
        :errors [(str "missing SKILL.md: " sp)] :warnings [] :loaded? false}
       (validate-file sp opts)))))

(defonce state (atom {:disc {:skills [] :collisions []} :trusted {} :at nil}))

(defn scope-dirs
  "Skill scopes for a project dir: [{:scope :scope :path s :trusted? b}].
  Project scopes need trust (env BBA_SKILLS_TRUST=1 or a .trusted file in the
  skills root); user scopes are always on; BBA_SKILLS_EXTRA adds roots."
  [& [project-dir]]
  (let [pd (or project-dir (str (fs/cwd)))
        home (System/getProperty "user.home")
        mk (fn [scope path]
             (let [trusted? (or (= scope :user)
                                (= "1" (get (System/getenv) "BBA_SKILLS_TRUST"))
                                (fs/exists? (str (fs/path path ".trusted"))))]
               (when (fs/exists? path)
                 {:scope scope :path (str path) :trusted? trusted?})))]
    (remove nil?
            (concat
             [(mk :project (str (fs/path pd ".agents" "skills")))
              (mk :project (str (fs/path pd ".bba" "skills")))
              (mk :user (str (fs/path home ".agents" "skills")))
              (mk :user (str (fs/path home ".claude" "skills")))
              (mk :user (str (fs/path home ".bba" "skills")))]
             (when-let [x (not-empty (str (get (System/getenv) "BBA_SKILLS_EXTRA")))]
               (map (fn [p] (mk :extra p))
                    (remove str/blank? (map str/trim (str/split x (re-pattern java.io.File/pathSeparator))))))))))

(defn scan-dir
  "Skill subdirectories of a skills root: [{:dir :location :scope}]. Bounded:"
  [root]
  (letfn [(walk [d depth acc]
            (if (> depth 4)
              acc
              (reduce (fn [acc f]
                        (let [n (str (fs/file-name f))]
                          (cond
                            (str/starts-with? n ".") acc
                            (and (fs/regular-file? f) (= "SKILL.md" n) (= depth 1))
                            (conj acc {:dir (str (fs/parent f)) :location (str f)
                                       :scope (:scope root)})
                            (fs/directory? f) (walk f (inc depth) acc)
                            :else acc)))
                      acc
                      (sort-by str (fs/list-dir d)))))]
    (walk (str (:path root)) 0 [])))

(defn discover
  "Scan every scope, parse+validate leniently, dedupe by name with project
  over user precedence. Returns {:skills [...] :collisions [...]}."
  [& [opts]]
  (let [rows (mapcat (fn [s]
                       (when (:trusted? s)
                         (mapv #(assoc % :scope (:scope s)) (scan-dir s))))
                     (scope-dirs (:project-dir opts)))
         vrows (mapv (fn [r] (assoc (validate-dir (:dir r)) :scope (:scope r)))
                     rows)
         by-name (reduce (fn [acc s]
                           (let [nm (str (:name s))
                                 k (if (str/blank? nm) (str "?" (:location s)) nm)
                                 prev (get (:skills acc) k)]
                             (cond
                               (nil? prev) (update acc :skills assoc k s)
                               ;; an invalid copy never shadows one that loads
                               (and (:loaded? s) (not (:loaded? prev)))
                               (update acc :skills assoc k s)
                               (not (:loaded? s)) acc
                               :else (update acc :collisions conj
                                             (str nm ": keeping " (:location prev)
                                                  ", shadowed " (:location s))))))
                         {:skills {} :collisions []}
                         vrows)]
    {:skills (vec (sort-by #(str (or (:name %) (:dir-name %))) (vals (:skills by-name))))
     :collisions (vec (:collisions by-name))}))

(defn rescan!
  "Re-discover skills for a project dir (default: the process cwd) and remember
  them. Returns the discovery result."
  [& [project-dir]]
  (let [d (discover {:project-dir project-dir})]
    (reset! state {:disc d :trusted (into {} (map (fn [s] [(:path s) (:trusted? s)]) (scope-dirs project-dir)))
                   :at (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss") (java.util.Date.))})
    d))

(defn catalog
  "Tier-1 text: one line per loaded skill. Empty when none."
  [skills]
  (if (empty? skills)
    ""
    (str/join NL (map (fn [s]
                        (str "- " (:name s) ": " (:description s)
                             " (SKILL.md: " (:location s) ")"))
                      (filter :loaded? skills)))))

(defn resources
  "Files bundled with a skill, relative to its directory: scripts/, references/,
  assets/ and anything else except SKILL.md. Hidden files skipped, capped at n."
  ([dir] (resources dir 50))
  ([dir n]
   (->> (fs/glob dir "**")
        (filter fs/regular-file?)
        (map #(str (fs/relativize dir %)))
        (remove #{"SKILL.md"})
        sort
        (take n)
        vec)))

(defn activate
  "Tier-2 activation: the skill body wrapped in skill_content tags, plus the
  bundle listing and base dir. {:body? false} returns metadata only."
  ([disc name] (activate disc name nil))
  ([disc skill-name opts]
   (let [s (some (fn [x] (when (= (str (:name x)) (str skill-name)) x))
                 (:skills disc))]
     (cond
       (nil? s) {:error (str "no such skill: " (pr-str skill-name))}
       (not (:loaded? s)) {:error (str "skill did not load: " skill-name)}
       :else
       (merge {:name (:name s)
               :description (:description s)
               :dir (:dir s)
               :resources (resources (:dir s))}
              (when (not= false (:body? opts))
                {:content (str "<skill_content name=" QD (:name s) QD ">" NL
                               (:body s)
                               NL NL "Skill directory: " (:dir s)
                               NL "Relative paths in this skill are relative to the skill directory."
                               (when-let [rr (seq (resources (:dir s)))]
                                 (str NL "<skill_resources>" NL
                                      (str/join NL rr)
                                      NL "</skill_resources>"
                                      NL "Read these files only when the instructions call for them."))
                               NL "</skill_content>")}))))))

(defn catalog-prompt
  "The tier-1 instruction block for the tool description; empty when none."
  [disc]
  (let [s (filterv :loaded? (:skills disc))]
    (if (empty? s)
      ""
      (str "When a user task matches one of these skills, call activate_skill" NL
           "with that skill's name before proceeding. Relative paths inside a" NL
           "skill resolve against its skill directory (returned by the call)." NL NL
           (catalog s)))))

(defn- loaded-names
  []
  (mapv :name (filterv :loaded? (:disc @state))))

(defn- tool-description
  "activate_skill description with the live catalog embedded (tier 1)."
  []
  (let [p (catalog-prompt (:disc @state))]
    (if (str/blank? p)
      "Load instructions for an installed Agent Skill by name."
      p)))

(defn reregister-tool!
  "(Re)register activate_skill with a fresh catalog in its description."
  []
  (ext/register-tool!
   {:name "activate_skill"
    :description (tool-description)
    :input-schema {:type "object"
                   :properties {:name {:type "string"
                                       :description "The skill name (see list above)"}}
                   :required ["name"]}
    :handler (fn [{:keys [name]} _ctx]
               (let [r (activate (:disc @state) name)]
                 (if (:error r)
                   {:content (:error r) :is-error true}
                   (:content r))))}))

(defn show-summary
  "One line per skill for the /skills command."
  []
  (let [{:keys [skills collisions]} (:disc @state)
        loaded (filterv :loaded? skills)
        bad (remove :loaded? skills)]
    (println (str (count loaded) " loaded skill(s)"
                  (when (seq bad) (str ", " (count bad) " invalid"))
                  (when (seq collisions) (str ", " (count collisions) " collision(s)")) ":"))
    (doseq [s (sort-by :name loaded)]
      (println (str "  " (:name s) " - " (:description s))))
    (doseq [w collisions] (println (str "  warn: " w)))
    (doseq [b bad]
      (println (str "  invalid: " (or (:name b) (:dir-name b)) " - "
                    (str/join "; " (or (:errors b) [])))))))

(defn show-resources
  "Print the bundle of one skill."
  [name]
  (let [s (some (fn [x] (when (= (str (:name x)) (str name)) x)) (:skills (:disc @state)))]
    (if s
      (let [rr (resources (:dir s))]
        (println (str (:name s) " bundle (" (:dir s) "):"))
        (if (seq rr)
          (doseq [f rr] (println (str "  " f)))
          (println "  (no scripts/, references/ or assets/)")))
      (println (str "no such skill: " name)))))

(defn show-validation
  "Strict check of one skill directory or SKILL.md, like skills-ref validate.
  Returns true when valid."
  [path]
  (let [p (str (fs/absolutize (fs/expand-home (str/trim path))))
        r (cond (fs/directory? p) (validate-dir p {:strict? true})
                (fs/regular-file? p) (validate-file p {:strict? true})
                :else {:location p :errors [(str "no such file or directory: " p)] :warnings []})]
    (if (empty? (:errors r))
      (println (str "valid: " (:name r) " (" (:location r) ")"))
      (do (println (str "invalid: " (:location r)))
          (doseq [e (:errors r)] (println (str "  error: " e)))))
    (doseq [w (:warnings r)] (println (str "  warn: " w)))
    (empty? (:errors r))))

(ext/register-command! "skills"
  (fn [args _ctx]
    (let [a (str/trim (str args))]
      (cond
        (or (= a "reload") (= a "rescan")) (do (rescan! (:cwd _ctx)) (reregister-tool!) (show-summary))
        (str/starts-with? a "show ") (show-resources (str/trim (subs a 5)))
        (str/starts-with? a "validate ") (show-validation (subs a 9))
        (= a "") (show-summary)
        :else (println "usage: /skills [reload | show NAME | validate PATH]")))))

(ext/on! :session-start
  (fn [_payload ctx]
    (rescan! (:cwd ctx))
    (reregister-tool!)))

(rescan!)
(reregister-tool!)
