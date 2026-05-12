(ns piquine.examples.stack-picker
  "Multi-select picker across sibling categories.

  Flow: pick category, multi-select items, return to category list with a
  count badge; repeat for other categories; Ctrl-D to confirm.

  Categories are keyed by keyword in :selected. handle-selection routes
  based on whether we are at the root level or inside a category."
  (:require
   [clojure.string :as str]
   [piquine.picker :as picker]))

(def categories
  {:auth     "🔐 Auth"
   :database "🗄️  Database"
   :frontend "🖥️  Frontend"
   :infra    "🔧 Infrastructure"})

(def ^:private category-items
  {:auth     ["Email / Password" "OAuth 2.0" "SSO / SAML" "MFA / TOTP"
              "API Keys" "Magic Links" "Passkeys"]
   :database ["PostgreSQL" "MySQL" "SQLite" "MongoDB"
              "Redis" "DynamoDB" "Elasticsearch"]
   :frontend ["React" "Vue" "Svelte" "HTMX"
              "Tailwind CSS" "Alpine.js" "shadcn/ui"]
   :infra    ["Docker" "Kubernetes" "Terraform"
              "GitHub Actions" "Nix" "Fly.io" "Hetzner"]})

(defn- root-items [selected]
  (map (fn [[id label]]
         (let [n (count (get selected id))]
           (if (pos? n) (str label " (" n ")") label)))
       categories))

(defn- label->category-id [label]
  (let [stripped (str/replace label #" \(\d+\)$" "")]
    (some (fn [[id lbl]] (when (= lbl stripped) id)) categories)))

(defrecord StackPicker []
  picker/Picker

  (get-items [_ state]
    (let [level (:level state :root)]
      (if (= level :root)
        (root-items (:selected state {}))
        (get category-items level []))))

  (format-item [_ item] item)

  (preview [_ _selected state]
    (let [level    (:level state :root)
          selected (:selected state {})]
      (str
       (if (= level :root)
         "Enter: open category   Ctrl-D: confirm\n"
         (str (get categories level) "\nTab: mark   Enter: confirm category\n"))
       (when (some seq (vals selected))
         (str "\nSelected so far:\n"
              (str/join "\n"
                        (for [[cat-id items] selected :when (seq items)]
                          (str (get categories cat-id) ": "
                               (str/join ", " items)))))))))

  (multi-select? [_] true)

  (handle-selection [_ items state]
    (let [level (:level state :root)]
      {:done? false
       :state
       (if (= level :root)
         ;; Enter on a category -> navigate into it
         (if-let [cat-id (label->category-id (first items))]
           (assoc state :level cat-id)
           state)
         ;; Inside a category -> store items and return to root
         (-> state
             (assoc-in [:selected level] (vec items))
             (assoc :level :root)))}))

  (execute [_ state]
    (let [selected (:selected state {})]
      (if (every? empty? (vals selected))
        (println "Nothing selected.")
        (do
          (println "\nYour stack:\n")
          (doseq [[cat-id items] selected :when (seq items)]
            (println (str (get categories cat-id) ":"))
            (doseq [item items] (println (str "  • " item)))
            (println))))
      selected)))

(defn create-stack-picker [] (->StackPicker))
