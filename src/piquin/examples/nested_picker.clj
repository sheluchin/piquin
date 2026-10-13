(ns piquin.examples.nested-picker
  "Three-level nested multi-select picker over plain alphanumeric items.
  Preview shows ROT13 of each item and the running collection."
  (:require
   [clojure.string :as str]
   [piquin.picker :as picker]))

;; ---------------------------------------------------------------------------
;; Data
;; ---------------------------------------------------------------------------

(def ^:private root-items ["Alpha" "Beta" "Gamma" "Delta"])

(def ^:private group-items
  (into {} (for [g root-items]
             [g (mapv #(str g "-" %) ["01" "02" "03" "04" "05"])])))

(def ^:private leaf-items
  (into {} (for [[_ children] group-items
                 c children]
             [c (mapv #(str c (char (+ (int \a) %))) (range 5))])))

(def back-sentinel "← back")

(defn- has-children? [item]
  (or (contains? group-items item)
      (contains? leaf-items item)))

(defn- children-of [item]
  (or (get group-items item)
      (get leaf-items item)
      []))

;; ---------------------------------------------------------------------------
;; ROT13
;; ---------------------------------------------------------------------------

(defn rot13 [s]
  (apply str
         (map (fn [c]
                (if (Character/isLetter c)
                  (let [base (if (Character/isUpperCase c) (int \A) (int \a))]
                    (-> c int (- base) (+ 13) (mod 26) (+ base) char))
                  c))
              s)))

;; ---------------------------------------------------------------------------
;; Picker
;; ---------------------------------------------------------------------------

(defrecord NestedPicker []
  picker/Picker

  (get-items [_ state]
    (let [path (:path state [])]
      (if (empty? path)
        root-items
        ;; back-sentinel at the END so it appears at the top of fzf's reversed
        ;; display and the cursor doesn't start on it.
        (conj (vec (children-of (last path))) back-sentinel))))

  (format-item [_ item] item)

  (preview [_ selected state]
    (let [collected  (:collected state {})
          curr-items (remove #{back-sentinel} selected)
          line       (fn [item] (str "  " item " → " (rot13 item)))]
      (str/join "\n\n"
                (remove nil?
                        [(when (seq curr-items)
                           (str "Current:\n" (str/join "\n" (map line curr-items))))
                         (let [entries (for [[path items] (sort-by (comp str first) collected)
                                             :when (seq items)]
                                         (str "  [" (str/join " › " path) "]\n"
                                              (str/join "\n" (map line items))))]
                           (str "Collected"
                                (if (seq entries)
                                  (str ":\n" (str/join "\n" entries))
                                  ": (none yet)")))]))))

  (multi-select? [_] true)

  (handle-selection [_ selected-items state]
    (let [path (:path state [])]
      {:done? false
       :state
       (cond
         ;; "← back" anywhere in selection -> go up one level
         (some #{back-sentinel} selected-items)
         (assoc state :path (vec (butlast path)))

         ;; Single non-leaf item -> navigate in
         (and (= 1 (count selected-items))
              (has-children? (first selected-items)))
         (assoc state :path (conj path (first selected-items)))

         ;; Otherwise -> accumulate selected items at this level and go up
         :else
         (let [items (vec (remove #{back-sentinel} selected-items))]
           (-> state
               (update-in [:collected path] (fnil into []) items)
               (assoc :path (vec (butlast path))))))}))

  (execute [_ state]
    (let [collected (:collected state {})]
      (if (every? empty? (vals collected))
        (println "Nothing collected.")
        (do
          (println "\nCollected items:\n")
          (doseq [[path items] (sort-by (comp str first) collected)
                  :when (seq items)]
            (println (str "  [" (str/join " › " path) "]"))
            (doseq [item items]
              (println (str "    " item " → " (rot13 item))))
            (println))))
      collected)))

(defn create-nested-picker [] (->NestedPicker))
