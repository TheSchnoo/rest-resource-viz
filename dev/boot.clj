(ns boot
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [boot.core :refer :all]
            [boot.util :as util]))

(defn set-system-properties!
  "Set a system property for each entry in the map m."
  [m]
  (doseq [kv m]
    (System/setProperty (-> kv key str) (-> kv val str))))

(defn apply-conf!
  "Calls boot.core/set-env! with the content of the :env key and
  System/setProperty for all the key/value pairs in the :props map."
  [conf]
  (util/dbug "Applying conf:\n%s\n" (util/pp-str conf))
  (let [env (:env conf)
        props (:props conf)]
    (apply set-env! (reduce #(into %2 %1) [] env))
    (assert (or (nil? props) (map? props))
            (format "Option :props should be a map, was %s." (pr-str props)))
    (assert (every? #(and (string? (key %)) (string? (val %))) props)
            (format "Option :props does not contain only strings, was %s" (pr-str props)))
    (set-system-properties! props)))

(s/def :resources/group-id string?)
(s/def :resources/format string?)
(s/def :resources/version string?)
(s/def :resources/module (s/or :string string? :symbol symbol?))
(s/def :resources/modules (s/coll-of :resources/module))

(s/def :repository/url string?)
(s/def :repository/snapshots boolean?)

(s/def :conf/repo-definition (s/keys :req-un [:repository/url :repository/snapshots]))
(s/def :conf/repositories (s/coll-of (s/cat :name string? :repo-definition :conf/repo-definition)))


(s/def :conf/resources (s/keys :req-un [:resources/group-id :resources/version
                                        :resources/format :resources/modules]))

(s/def :conf/clojars-dep (s/cat :group-slash-artifact symbol? :version string?))
(s/def :conf/additional-deps (s/coll-of :conf/clojars-dep))

(s/def :extractor/conf (s/keys :req-un [:conf/resources :conf/additional-deps :conf/repositories]))

(defn calculate-resource-deps
  "Calculate the dependency vector for artifacts that share group-id and
  names

  See the spec on the input for accepted keys and their meaning.

  The vector will be calculated by using:

    [(format resources-format resources-group-id module-name) resources-version]

  For each entry in :resources-modules."
  [{:keys [group-id format version modules] :as resources}]
  (s/assert* :conf/resources resources)
  (mapv #(vector (-> format (clojure.core/format group-id %) symbol) version)
        modules))

(defn conj-new-coords
  [res-conf old-coords]
  (-> old-coords
      (concat (-> #{}
                  (into (:additional-deps res-conf))
                  (into (calculate-resource-deps (:resources res-conf)))
                  vec))
      distinct
      vec))

(defn conj-new-repositories
  [res-conf old-repositories]
  (-> old-repositories
      (concat (:repositories res-conf))
      distinct
      vec))

(defn add-file-conf!
  "Returns a vector containing the rest-resources coordinates given for
  the modules. It reads the modules collection names from a file called
  modules.edn."
  [current-conf res-conf-path]
  (let [file-conf (->> res-conf-path
                       io/file
                       slurp
                       edn/read-string)]
    (s/assert* :extractor/conf file-conf)
    (-> current-conf
        (update-in [:env :dependencies] (partial conj-new-coords file-conf))
        (update-in [:env :repositories] (partial conj-new-repositories file-conf)))))

;; Spec for our little DSL

(s/def ::env map?)
(s/def ::task-sym symbol?)
(s/def ::task-form (s/coll-of ::task-sym :kind list?))
(s/def ::pipeline (s/alt :task-form ::task-sym
                         :comp-of-task-forms (s/cat :comp #{'comp} :tasks (s/* ::task-form))))
(s/def ::conf (s/keys :req-un [::env ::pipeline]))

(comment
  (s/explain :boot/pipeline '(arst)) ;; val: () fails spec: :boot/pipeline predicate: (alt :task-form :boot/task-sym :comp-of-task-forms (cat :comp #{(quote comp)} :tasks (* :boot/task-form))),  Insufficient input
  (s/explain :boot/pipeline '(arst)) ;; val: () fails spec: :boot/task-form at: [:tasks] predicate: :boot/task-form,  Insufficient input
  (s/explain :boot/pipeline '(comp (arst))))

(defmacro resolve-boot-tasks!
  "Resolve the symbol to a boot task (checking meta as well)"
  [syms]
  `(->> ~syms
        (mapv (fn [sym#]
                (let [resolved-task# (resolve sym#)
                      resolved-meta# (meta resolved-task#)]
                  (when (>= @boot.util/*verbosity* 3)
                    (boot.util/dbug* "Resolved %s (meta follows)\n%s\n" sym# resolved-meta#))
                  (when (:boot.core/task resolved-meta#)
                    resolved-task#))))
        (remove nil?)))

(defmacro task-symbols
  "Return a vector of task namespace/task-name

  The user-provided namespace will be first in the vector, followed by
  boot.task.built-in and boot.user. Typically you should try resolving
  in this order."
  [user-ns task-name]
  `(mapv (fn [ns#] (symbol (str ns#) (str ~task-name)))
         ;; order counts here
         (->> (conj '(boot.task.built-in boot.user) ~user-ns)
              (remove nil?))))

(defn middlewares
  "Produce boot middleware from configuration data"
  [conf]
  (do (boot/apply-conf! conf)
      (boot.util/dbug* "Spec Validation: %s\n" (boot.util/pp-str (or (clojure.spec.alpha/explain-data :boot/conf conf) ::success)))
      (clojure.spec.alpha/assert* :boot/conf conf)
      (let [normalized-tasks# (->> conf
                                   :pipeline
                                   flatten
                                   (remove (comp #{"comp"} name)) ;; odd (= 'comp %) works in the repl non from cmd line
                                   (mapv #(vector (-> % name symbol) (some-> % namespace symbol))))]
        (boot.util/dbug* "Normalized tasks: %s\n" (boot.util/pp-str normalized-tasks#))
        ;;
        ;; Phase 1: require namespace and throw if cannot resolve
        (doseq [[task-name# ns#] normalized-tasks#]
          (when ns#
            (boot.util/info "Requiring %s...\n" (util/pp-str ns#))
            (require ns#)))
        ;; Phase 2: compose middlewares
        (reduce (fn [acc# [task-name# ns#]]
                  (let [task-syms# (boot/task-symbols ns# task-name#)
                        resolved-vars# (boot/resolve-boot-tasks! task-syms#)]
                    (boot.util/dbug* "Resolution order: %s\n" (util/pp-str task-syms#))
                    (if (seq resolved-vars#)
                      (comp acc# (apply (first resolved-vars#)
                                        (mapcat identity (->> task-name# name keyword (get conf)))))
                      (throw (ex-info (str "Cannot resolve either " (clojure.string/join " or " task-syms#) ", is the task spelled correctly and its dependency on the classpath?")
                                      {:task-syms task-syms#})))))
                identity
                normalized-tasks#))))

(defmacro defedntask
  "Create boot tasks based on an edn conf.

  The body of this macro will be evaluated and the expected result has
  to be in the following form:

      {:env {:resource-paths #{\"resources\"}
             :source-paths #{\"src/web\" \"src/shared\"}
             :dependencies '[[org.clojure/clojure \"1.9.0-alpha14\"]
                             [adzerk/boot-cljs \"2.0.0-SNAPSHOT\" :scope \"test\"]
                             [org.clojure/clojurescript \"1.9.456\"  :scope \"test\"]
                             [reagent \"0.6.0\"]
                             ...]}
       :pipeline '(comp (pandeiro.boot-http/serve)
                        (watch)
                        (powerlaces.boot-cljs-devtools/cljs-devtools)
                        (powerlaces.boot-figreload/reload)
                        (adzerk.boot-cljs-repl/cljs-repl)
                        (adzerk.boot-cljs/cljs))
       :cljs {:source-map true
              :optimizations :advanced
              :compiler-options {:closure-defines {\"goog.DEBUG\" false}
                                 :verbose true}}
       :cljs-devtools {...}}

  One simple convention is adopted here, the keys that match a task name
  will provide options to that task.

  The `:pipeline` key will be the generated task."
  [sym & forms]
  (let [[heads [bindings & tails]] (split-with (complement vector?) forms)
        new-forms (reverse (-> (into `(~sym boot.core/deftask) heads)
                               (conj bindings `(boot/middlewares (do ~@tails)))))]
    (util/dbug* "defedntask generated:\n%s\n" (util/pp-str new-forms))
    `(~@new-forms)))
