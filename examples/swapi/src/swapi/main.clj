(ns swapi.main
  (:require
   [piquine.fzf.runtime :as rt]
   [piquine.picker :as picker]
   [swapi.picker :as sp]
   [taoensso.timbre :as log]))

(log/set-min-level! :fatal)

(defn -main [& _args]
  (log/set-min-level! :fatal)
  (try
    (let [p     (sp/create-swapi-picker)
          chart (picker/picker->statechart p {:path [] :query-tree {}})
          {:keys [state]} (rt/run! chart
                                    :session-id :swapi
                                    :header "Enter: drill/mark  Tab: multi-mark  Ctrl-D: done"
                                    :preview-fn (fn [session-id selection]
                                                  (picker/picker-preview p
                                                                          (rt/data-snapshot session-id [:picker/items :picker/state])
                                                                          selection)))]
      (picker/execute p state)
      (shutdown-agents))
    (catch Exception e
      (println (str "Error: " (ex-message e)))
      (.printStackTrace e)
      (System/exit 1))))
