(ns swapi.picker
  "Interactive SWAPI GraphQL query builder built on piquine.

  State shape:
    :path        - vec of attribute keywords; where we are in the query tree
    :query-tree  - nested map being built, e.g. {:a {:b nil :c {:d nil}}}
                   nil values are leaves; maps are joins

  Each fzf level shows reachable attributes from the current context plus
  '✓ done' and '← back' sentinels. Single-select drills in; multi-select
  (Tab + Enter) adds the marked fields as leaves at the current path. The
  preview pane shows the assembled EQL and live query result."
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [piquine.picker :as picker]
   [swapi.api :as api]))

(def ^:private root-attrs #{:swapi.types/Root})
(def ^:private back-sentinel "← back")
(def ^:private done-sentinel "✓ done")

;; ---------------------------------------------------------------------------
;; Context derivation
;; ---------------------------------------------------------------------------

(defn- step-context
  "Given the current attr set and a chosen field, return the next attr set
  the way phuzql did: #{chosen} plus the keys of its reachable children."
  [attrs chosen]
  (let [reachable (api/reachable-attributes attrs)
        children  (get reachable chosen)]
    (into #{chosen} (keys children))))

(defn- context-for-path
  "Walk the path from root, returning the attr set at the end."
  [path]
  (reduce step-context root-attrs path))

;; ---------------------------------------------------------------------------
;; EQL assembly
;; ---------------------------------------------------------------------------

(defn- prune-empty
  "Drop join branches that have no leaves anywhere underneath them. GraphQL
  doesn't accept empty selection sets, so a partially-drilled subtree with
  no leaves is invalid; remove it before assembling the query."
  [tree]
  (when (map? tree)
    (let [pruned (reduce-kv (fn [m k v]
                              (cond
                                (nil? v)              (assoc m k nil)  ; leaf
                                (not (map? v))        (assoc m k v)
                                :else (let [p (prune-empty v)]
                                        (if (seq p) (assoc m k p) m))))
                            {}
                            tree)]
      (when (seq pruned) pruned))))

(defn tree->eql
  "Convert the nested query-tree to an EQL query, dropping any joins with
  no leaves under them."
  [tree]
  (let [pruned (prune-empty tree)]
    (vec (for [[k v] pruned]
           (if (nil? v)
             k
             {k (tree->eql v)})))))

;; ---------------------------------------------------------------------------
;; Picker
;; ---------------------------------------------------------------------------

(defrecord SwapiPicker []
  picker/Picker

  (get-items [_ state]
    (let [path     (:path state [])
          attrs    (context-for-path path)
          fields   (sort (keys (api/reachable-attributes attrs)))]
      (concat
       (when (seq path) [back-sentinel])
       [done-sentinel]
       fields)))

  (format-item [_ item]
    (if (keyword? item)
      (str (namespace item) "/" (name item))
      item))

  (preview [this selections state]
    ;; Show what the query WOULD look like if Enter were pressed now: marked
    ;; selections are added as prospective leaves at the current path.
    (let [path     (:path state [])
          marked   (filter keyword? selections)
          prospect (reduce (fn [tree field]
                             (let [pv (vec (concat path [field]))]
                               (if (nil? (get-in tree pv))
                                 (assoc-in tree pv nil)
                                 tree)))
                           (:query-tree state {})
                           marked)
          eql      (tree->eql prospect)]
      (str
       "Path:\n  "
       (if (seq path)
         (str/join "\n  " (map #(picker/format-item this %) path))
         "(root)")
       (when (seq marked)
         (str "\n\nMarked at this level:\n  "
              (str/join "\n  " (map #(picker/format-item this %) marked))))
       "\n\nQuery (with marks applied as leaves):\n"
       (with-out-str
         (binding [pprint/*print-right-margin* 70]
           (pprint/pprint eql)))
       "\nResult:\n"
       (if (empty? eql)
         "(empty query — pick some fields)"
         (try
           (with-out-str
             (binding [pprint/*print-right-margin* 70]
               (pprint/pprint (api/run-query eql))))
           (catch Exception e
             (str "(query not ready or error)\n" (.getMessage e))))))))

  (multi-select? [_] true)

  (handle-selection [_ items state]
    (let [path        (:path state [])
          items       (remove nil? items)
          add-leaves  (fn [s leaves]
                        (reduce (fn [s f]
                                  (let [pv (vec (concat [:query-tree] path [f]))]
                                    (if (nil? (get-in s pv))
                                      (assoc-in s pv nil)
                                      s)))
                                s
                                (filter keyword? leaves)))]
      (cond
        ;; ✓ done -> commit any pending marks as leaves, then terminate
        (some #{done-sentinel} items)
        {:done? true :state (add-leaves state items)}

        ;; ← back -> pop the path (ignores any tab marks at this level)
        (some #{back-sentinel} items)
        {:done? false :state (update state :path (comp vec butlast))}

        ;; Single field, no marks -> drill in
        (and (= 1 (count items)) (keyword? (first items)))
        (let [field (first items)]
          {:done? false
           :state (-> state
                      (update :path conj field)
                      (assoc-in (concat [:query-tree] path [field]) {}))})

        ;; Multi-selection -> add each as a leaf at current path
        :else
        {:done? false :state (add-leaves state items)})))

  (execute [_ state]
    (let [eql (tree->eql (:query-tree state {}))]
      (println "\nQuery:")
      (pprint/pprint eql)
      (cond
        (empty? eql)
        (println "\n(no query — nothing to run)")

        :else
        (do
          (println "\nResult:")
          (try
            (pprint/pprint (api/run-query eql))
            (catch Exception e
              (println "Query failed:")
              (println " " (ex-message e))
              (let [cause (ex-message (ex-cause e))]
                (when cause (println "  cause:" cause)))
              (println "\nTip: drilling into a field without marking any leaves under it")
              (println "produces an empty selection set, which GraphQL rejects. Either")
              (println "mark at least one leaf at every level, or back out before pressing")
              (println "Ctrl-D.")))))
      eql)))

(defn create-swapi-picker [] (->SwapiPicker))
