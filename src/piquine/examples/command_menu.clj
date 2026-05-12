(ns piquine.examples.command-menu
  "Two-level menu picker. Root has File/Edit/View/Tools; each submenu has
  individual commands. Selecting a command produces a result and the chart
  terminates."
  (:require
   [piquine.picker :as picker]))

(def ^:private command-results
  {"New File"      {:message "Created new file"          :success true}
   "Open File"     {:message "File dialog opened"        :success true}
   "Save File"     {:message "File saved"                :success true}
   "Save As"       {:message "Save As dialog opened"     :success true}
   "Exit"          {:message "Application exiting"       :success true}
   "Cut"           {:message "Cut to clipboard"          :success true}
   "Copy"          {:message "Copied to clipboard"       :success true}
   "Paste"         {:message "Pasted from clipboard"     :success true}
   "Find"          {:message "Find dialog opened"        :success true}
   "Replace"       {:message "Find & Replace dialog"     :success true}
   "Zoom In"       {:message "Zoomed in"                 :success true}
   "Zoom Out"      {:message "Zoomed out"                :success true}
   "Full Screen"   {:message "Toggled full screen"       :success true}
   "Show Sidebar"  {:message "Toggled sidebar"           :success true}
   "Preferences"   {:message "Preferences dialog opened" :success true}
   "Extensions"    {:message "Extensions manager opened" :success true}
   "Terminal"      {:message "Terminal opened"           :success true}
   "Debug Console" {:message "Debug console opened"      :success true}})

(def ^:private emoji-map
  {"File" "📁" "Edit" "✏️" "View" "👁️" "Tools" "🛠️"
   "New File" "📄" "Open File" "📂" "Save File" "💾" "Save As" "💾"
   "Exit" "🚪" "Cut" "✂️" "Copy" "📋" "Paste" "📌"
   "Find" "🔍" "Replace" "🔄" "Zoom In" "🔍" "Zoom Out" "🔍"
   "Full Screen" "🖥️" "Show Sidebar" "📊" "Preferences" "⚙️"
   "Extensions" "🔌" "Terminal" "💻" "Debug Console" "🐛"})

(def ^:private submenus
  {:file  ["New File" "Open File" "Save File" "Save As" "Exit"]
   :edit  ["Cut" "Copy" "Paste" "Find" "Replace"]
   :view  ["Zoom In" "Zoom Out" "Full Screen" "Show Sidebar"]
   :tools ["Preferences" "Extensions" "Terminal" "Debug Console"]})

(def ^:private label->submenu
  {"File" :file "Edit" :edit "View" :view "Tools" :tools})

(defrecord CommandMenuPicker []
  picker/Picker

  (get-items [_ state]
    (let [level (:level state :root)]
      (if (= level :root)
        ["File" "Edit" "View" "Tools"]
        (get submenus level []))))

  (format-item [_ item]
    (if-let [emoji (get emoji-map item)]
      (str emoji " " item)
      item))

  (preview [_ selected-items state]
    (when-let [item (first selected-items)]
      (if (= (:level state :root) :root)
        (str "Open " item " menu")
        (str "Execute: " item))))

  (multi-select? [_] false)

  (handle-selection [_ items state]
    (let [item (first items)
          level (:level state :root)]
      (if (= level :root)
        {:done? false
         :state (assoc state :level (label->submenu item :root))}
        {:done? true
         :state (assoc state :result (get command-results item))})))

  (execute [_ state]
    (when-let [r (:result state)]
      (println (:message r))
      r)))

(defn create-command-menu-picker [] (->CommandMenuPicker))
