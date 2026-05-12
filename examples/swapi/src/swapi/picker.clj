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

(defn tree->eql
  "Convert the nested query-tree to an EQL query.
  Map values that are nil become bare keywords (leaves); non-nil values
  become joins."
  [tree]
  (vec (for [[k v] tree]
         (if (nil? v)
           k
           {k (tree->eql v)}))))

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

  (preview [_ _selections state]
    (let [eql (tree->eql (:query-tree state {}))]
      (str
       "Path:\n  "
       (if (seq (:path state))
         (str/join "\n  " (map #(picker/format-item (->SwapiPicker) %) (:path state)))
         "(root)")
       "\n\nQuery:\n"
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
    (let [path (:path state [])
          items (remove nil? items)]
      (cond
        ;; ✓ done -> terminate
        (some #{done-sentinel} items)
        {:done? true :state state}

        ;; ← back -> pop the path
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
        {:done? false
         :state (reduce (fn [s field]
                          (if (and (keyword? field)
                                   (nil? (get-in s (concat [:query-tree] path [field]))))
                            (assoc-in s (concat [:query-tree] path [field]) nil)
                            s))
                        state
                        items)})))

  (execute [_ state]
    (let [eql (tree->eql (:query-tree state {}))]
      (println "\nQuery:")
      (pprint/pprint eql)
      (when (seq eql)
        (println "\nResult:")
        (pprint/pprint (api/run-query eql)))
      eql)))

(defn create-swapi-picker [] (->SwapiPicker))
