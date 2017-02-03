(ns ^{:doc "The namespace containing state and model-related functions"
      :author "Andrea Richiardi"}
    rest-resources-viz.model
  (:require [clojure.spec :as s]
            [reagent.core :as r]
            [rest-resources-viz.xform :as xform])
  (:require-macros [rest-resources-viz.logging :as log]))

(def init-state {:attrs {:graph {:margin {:top 20 :right 20 :bottom 20 :left 20}
                                 :width 960
                                 :height 840}
                         :node {:base-radius 6
                                :default-color "steelblue"
                                :strength -100}
                         :link {:distance 42}
                         :text {:dx 8}
                         :family-panel {:font-size-min "9"}
                         :tooltip {:width 100 :height 20
                                   :padding 10 :stroke-width 2
                                   :rx 5 :ry 5
                                   :dx 2 :dy 4}
                         :family-colors ["#000000" "#0cc402" "#fc0a18" "#aea7a5" "#5dafbd" "#d99f07" "#11a5fe" "#037e43" "#ba4455" "#d10aff" "#9354a6" "#7b6d2b" "#08bbbb" "#95b42d" "#b54e04" "#ee74ff" "#2d7593" "#e19772" "#fa7fbe" "#62bd33" "#aea0db" "#905e76" "#92b27a" "#bbd766" "#878aff" "#4a7662" "#ff6757" "#fe8504" "#9340e1" "#2a8602" "#07b6e5" "#d21170" "#526ab3" "#015eff" "#bb2ea7" "#09bf91" "#90624c" "#bba94a" "#a26c05"]}
                 })

(defonce app-state (r/atom init-state))
(defonce graph-data-state (r/cursor app-state [:graph-data]))
(defonce attrs-state (r/cursor app-state [:attrs]))
(defonce clicked-js-node-state (r/cursor app-state [:clicked-js-node]))
(defonce hovered-js-node-state (r/cursor app-state [:hovered-js-node]))

(defn trash-graph-data! []
  (swap! app-state assoc :graph-data nil))

(defn reset-state! []
  (reset! app-state init-state))

(defn get-in-and-assign-kind
  "Similarly to get-in, tries to get from ks and also assoc the last
  selector as :kind.

  It assumes you can map over the retrieved thing, does not handle
  literal values (for now)."
  [m ks]
  (->> (get-in m ks)
       (mapv #(assoc % :kind (last ks)))))

(defn get-relationships []
  (let [rels (->> (concat (get-in-and-assign-kind @graph-data-state [:relationship])
                          (get-in-and-assign-kind @graph-data-state [:list-of-relationship])
                          (get-in-and-assign-kind @graph-data-state [:pagination-relationship])
                          (get-in-and-assign-kind @graph-data-state [:alias-relationship]))
                  (xform/unfold-relationships))]
    (log/debug "Relationships" (sort-by :name rels))
    (log/debug "Relationships count" (count rels))
    (log/debug "Relationships sample" (second rels))
    rels))

(defn get-relationships-by-target []
  (group-by :target @(r/track get-relationships)))

(defn get-relationships-by-source []
  (group-by :source @(r/track get-relationships)))

(defn node-radius
  "Return the radius for a node"
  [base-radius rel-count rel-total]
  ;; AR - arbitrary multiplier here, we are also good with failing in case the
  ;; total is zero
  (let [multiplier (* 3 (/ rel-count rel-total))]
    (+ base-radius (* base-radius multiplier))))

(defn relationship-bounds
  "Calculate lower and upper bounds for relationships

  Return a data structure like so:
    {:target-rel-lower-bound _
     :target-rel-upper-bound _
     :source-rel-lower-bound _
     :source-rel-upper-bound _}

  It requires the presence of :target-rel-count and :source-rel-count"
  [resources]
  (reduce
   (fn [{:keys [target-rel-lower-bound target-rel-upper-bound source-rel-lower-bound source-rel-upper-bound] :as totals}
        {:keys [target-rel-count source-rel-count]}]
     (cond
       (and (not source-rel-count) (not target-rel-count)) totals
       (and target-rel-count (< target-rel-count target-rel-lower-bound)) (assoc totals :target-rel-lower-bound target-rel-count)
       (and target-rel-count (> target-rel-count target-rel-upper-bound)) (assoc totals :target-rel-upper-bound target-rel-count)
       (and source-rel-count (< source-rel-count source-rel-lower-bound)) (assoc totals :source-rel-lower-bound source-rel-count)
       (and source-rel-count (> source-rel-count source-rel-upper-bound)) (assoc totals :source-rel-upper-bound source-rel-count)
       :else totals))
   {:target-rel-lower-bound 0
    :target-rel-upper-bound 0
    :source-rel-lower-bound 0
    :source-rel-upper-bound 0}
   resources))

(defn get-resources []
  (let [rels-by-target @(r/track get-relationships-by-target)
        rels-by-source @(r/track get-relationships-by-source)
        base-radius (get-in @attrs-state [:node :base-radius])
        resources (get-in-and-assign-kind @graph-data-state [:resource])
        resources (map #(merge %
                               {:target-rel-count (or (some->> % :id (get rels-by-target) count) 0)
                                :source-rel-count (or (some->> % :id (get rels-by-source) count) 0)})
                       resources)
        rel-bounds (relationship-bounds resources)
        resources (map #(assoc % :radius (node-radius base-radius
                                                      (or (:source-rel-count %) 0)
                                                      (:source-rel-upper-bound rel-bounds))) resources)]
    (log/debug "Resources" (sort-by :name resources))
    (log/debug "Resource count" (count resources))
    (log/debug "Resource sample" (first resources))
    resources))

(defn get-resources-by-id []
  {:post [(every? map? (map second %))]}
  (let [groups (group-by :id @(r/track get-resources))]
    (assert (every? #(= 1 (count %)) (map second groups))
            "There should be only one resource per :id, this might be a bug.")
    (->> groups
         (map #(vector (first %) (-> % second first))) ;; at this point I am sure there is only one
         (into {}))))

(defn get-families []
  (let [families (get-in-and-assign-kind @graph-data-state [:family])]
    (log/debug "Families" (sort-by :name families))
    (log/debug "Family count" (count families))
    (log/debug "Family sample" (second families))
    families))

(defn get-family-index-by-name []
  (let [families @(r/track get-families)]
    (->> families
         (map-indexed (fn [i v] [(:name v) i]))
         (into {}))))

;; AR - Make these functions pure so that we can test them
;; maybe move them to rest-resources-viz/xform
(defn get-resource-neighbors
  "Calculate neighbors for the resources

  Return a map:
    {resource1 #{neighbor-res3 neighbor-res4 ...}
     resource2 #{neighbor-res1 neighbor-res5 ...}}"[]
  (let [resources-by-id @(r/track get-resources-by-id)
        rels-by-target @(r/track get-relationships-by-target)
        rels-by-source @(r/track get-relationships-by-source)]
    (transduce identity
               (completing (fn [m res]
                             (assoc m res
                                    (into (->> (get rels-by-source (:id res))
                                               (map #(get resources-by-id (:target %)))
                                               (set))
                                          (->> (get rels-by-target (:id res))
                                               (map #(get resources-by-id (:source %))))))))
               {}
               (vals resources-by-id))))

(defn get-resource-neighbors-by-id
  "Same as get-resource-neighbors, but keys are ids and the values are
  set of ids as well (the neighbors)."
  []
  (let [resource-neighbors @(r/track get-resource-neighbors)]
    (->> resource-neighbors
         (map #(vector (-> % first :id) (->> % second (map :id) set)))
         (into {}))))

(defn get-js-nodes []
  (clj->js @(r/track! get-resources)))

(defn get-js-links []
  (clj->js @(r/track! get-relationships)))

(s/fdef get-node-color
  :args (s/cat :colors :graph/colors
               :family-index-by-name :graph/family-index-by-name
               :family-id :graph/family-id)
  :ret string?)

(defn get-node-color [colors family-index-by-name family-name]
  (get colors (get family-index-by-name family-name)))