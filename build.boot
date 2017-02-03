(def project 'rest-resources-viz)
(def version "0.1.0-SNAPSHOT")

(set-env! :source-paths #{"dev"})

(task-options!
 pom {:project     project
      :version     version
      :description "Transformations and visualizations for Cortex Rest resources"
      :url         "https://github.com/elasticpath/rest-resource-viz"
      :scm         {:url "https://github.com/elasticpath/rest-resource-viz.git"}
      :license     {"Apache License, Version 2.0"
                    "http://www.apache.org/licenses/LICENSE-2.0"}})

(require '[boot.util :as util]
         '[boot.pod :as pod]
         '[boot])

(defn add-resources-deps!
  "Returns a vector containing the rest-resources coordinates given for
  the modules. It reads the modules collection names from a file called
  modules.edn."
  [conf]
  (let [res-conf (:resources conf)
        coords (-> #{}
                   (into (:additional-deps res-conf))
                   (into (boot/calculate-resource-deps res-conf))
                   vec)]
    (update-in conf [:env :dependencies] #(-> % (concat coords) distinct vec))))

;;;;;;;;;;;;;;;
;; Extractor ;;
;;;;;;;;;;;;;;;

(def conf-extractor
  {:resources {:module-edn-path "resources/modules.edn"
               :resources-group-id "com.elasticpath.rest.definitions"
               :resources-format "%s/ep-resource-%s-api"
               :resources-version "0-SNAPSHOT"
               :additional-deps '[[com.elasticpath.rest.definitions/ep-resource-collections-api "0-SNAPSHOT"]
                                  [com.elasticpath.rest.definitions/ep-resource-base-api "0-SNAPSHOT"]
                                  [com.elasticpath.rest.definitions/ep-resource-controls-api "0-SNAPSHOT"]]}
   :env {:resource-paths #{"resources"}
         :source-paths #{"src/task" "src/shared"}
         :dependencies '[[org.clojure/clojure "1.9.0-alpha14"]
                         [org.clojure/tools.cli "0.3.5"]
                         [org.clojure/data.xml "0.2.0-alpha1"]
                         [org.clojure/data.zip "0.1.2"]
                         [org.clojure/test.check "0.9.0"]
                         [cheshire "5.6.3"]
                         [org.clojure/java.classpath "0.2.3"]
                         [com.rpl/specter "0.13.3-SNAPSHOT"]]}})

(deftask resource-data
  "The task generates the data from the resource parsing.

  It does NOT add it to the fileset, but calls
  rest-resources-viz.generator/-main and dumps the data in src/cljs (hard
  coded). It should not be part of the build pipeline."
  []
  (let [env (:env conf-extractor)
        pod-env (update env :directories concat (:source-paths env) (:resource-paths env))
        pod (future (pod/make-pod pod-env))]
    (with-pass-thru fs
      (boot.util/info "Generating resource data...\n")
      (pod/with-eval-in @pod
        (require 'rest-resources-viz.extractor)
        (rest-resources-viz.extractor/-main)))))

(deftask build-extractor
  "Build and install the project locally."
  []
  (comp (pom) (jar) (install)))

(deftask init-extractor
  "Start the dev interactive environment."
  []
  (with-pass-thru fs
    (let [new-conf (add-resources-deps! conf-extractor)]
      (util/dbug "Current conf:\n%s\n" (util/pp-str new-conf))
      (boot/apply-conf! new-conf))))

(deftask dev-extractor
  "Start the dev interactive environment."
  []
  (util/info "Starting interactive dev...\n")
  (comp (init-extractor)
        (repl :server true
              :port 5055)
        (wait)))

(deftask extract
  "Run the -main function in some namespace with arguments"
  []
  (comp (init-extractor)
        (with-pass-thru _
          (let [main-ns 'rest-resources-viz.extractor]
            (require main-ns)
            (if-let [f (ns-resolve main-ns '-main)]
              (f *args*)
              (throw (ex-info "No -main method found" {:main-ns main-ns})))))))

(deftask dev [] (dev-extractor))

;;;;;;;;;
;; WEB ;;
;;;;;;;;;

(def prod-deps '[[org.clojure/clojure "1.9.0-alpha14"]
                 [adzerk/boot-cljs "2.0.0-SNAPSHOT" :scope "test"]
                 [org.clojure/clojurescript "1.9.456"  :scope "test"]
                 [org.clojure/test.check "0.9.0"] ;; AR - at the moment we need it, see http://dev.clojure.org/jira/browse/CLJS-1792
                 [adzerk/env "0.4.0"]
                 [binaryage/oops "0.5.2"]
                 [cljsjs/d3 "4.3.0-3"]
                 [cljsjs/intersections "1.0.0-0"]
                 [reagent "0.6.0"]])

(def dev-deps (vec (into prod-deps
                         '[[powerlaces/boot-figreload "0.1.0-SNAPSHOT" :scope "test"]
                           [adzerk/boot-cljs-repl "0.3.3" :scope "test"]
                           [com.cemerick/piggieback "0.2.1"  :scope "test"]
                           [weasel "0.7.0"  :scope "test"]
                           [org.clojure/tools.nrepl "0.2.12" :scope "test"]
                           [binaryage/devtools "0.8.3" :scope "test"]
                           [powerlaces/boot-cljs-devtools "0.1.3-SNAPSHOT" :scope "test"]
                           [pandeiro/boot-http "0.7.6" :scope "test"]
                           [crisptrutski/boot-cljs-test "0.2.2" :scope "test"]])))

(def conf-web
  {:env {:resource-paths #{"resources"}
         :source-paths #{"src/web" "src/shared"}}})

(deftask init-prod-web []
  (boot/apply-conf! (-> conf-web
                        (assoc-in [:env :dependencies] prod-deps)))
  (require '[adzerk.boot-cljs])
  (def cljs (resolve 'adzerk.boot-cljs/cljs)))

(deftask init-dev-web []
  (boot/apply-conf! (-> conf-web
                        (assoc-in [:env :dependencies] dev-deps)))
  (require '[adzerk.boot-cljs]
           '[adzerk.boot-cljs-repl]
           '[powerlaces.boot-figreload]
           '[pandeiro.boot-http]
           '[adzerk.env]
           '[powerlaces.boot-cljs-devtools])
  (def cljs (resolve 'adzerk.boot-cljs/cljs))
  (def cljs-repl (resolve 'adzerk.boot-cljs-repl/cljs-repl))
  (def reload (resolve 'powerlaces.boot-figreload/reload))
  (def serve (resolve 'pandeiro.boot-http/serve))
  ;; (def dirac (resolve 'powerlaces.boot-cljs-devtools/dirac))
  (def cljs-devtools (resolve 'powerlaces.boot-cljs-devtools/cljs-devtools)))

(deftask dev-web []
  (init-dev-web)
  (comp (serve)
        (watch)
        (cljs-devtools)
        (reload :client-opts {:debug true})
        (cljs-repl :nrepl-opts {:port 5088})
        (cljs :source-map true
              :optimizations :none
              :compiler-options {:external-config
                                 {:devtools/config {:features-to-install [:formatters :hints]
                                                    :fn-symbol "λ"
                                                    :print-config-overrides true}}})))

(deftask build-web []
  (init-prod-web)
  (cljs :source-map true
        :optimizations :advanced
        :compiler-options {:closure-defines {"goog.DEBUG" false}
                           ;; :elide-asserts true
                           :pseudo-names true ;; TODO set to false for prod
                           :pretty-print false
                           :source-map-timestamp true
                           :parallel-build true
                           :verbose true
                           :compiler-stats true}) )

(def conf-tests
  {:env {:resource-paths #{"resources"}
         :source-paths #{"src/task" "test/task" "src/shared" "test/shared"}
         :dependencies (into (get-in conf-extractor [:env :dependencies])
                             '[[org.clojure/tools.namespace "0.3.0-alpha3"]
                               [metosin/boot-alt-test "0.3.0"]])}})

(deftask init-tests
  "Start the dev interactive environment."
  []
  (util/dbug "Current conf:\n%s\n" (util/pp-str conf-tests))
  (boot/apply-conf! conf-tests)
  (require '[metosin.boot-alt-test])
  (def alt-test (resolve 'metosin.boot-alt-test/alt-test)))

(ns-unmap *ns* 'test)

(deftask test
  "Testing once (dev profile)"
  []
  (init-tests)
  (alt-test))
