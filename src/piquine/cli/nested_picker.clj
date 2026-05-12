(ns piquine.cli.nested-picker
  (:require
   [piquine.examples.nested-picker :as nested]
   [piquine.fzf.runtime :as rt]
   [piquine.picker :as picker]
   [taoensso.timbre :as log]))

(log/set-min-level! :fatal)

(defn -main [& _args]
  (log/set-min-level! :fatal)
  (try
    (let [p     (nested/create-nested-picker)
          chart (picker/picker->statechart p {:path [] :collected {}})
          {:keys [state]} (rt/run! chart
                                    :session-id :nested-picker
                                    :header "Enter: select/navigate  Tab: mark  Ctrl-D: done"
                                    :preview-fn (fn [session-id selection]
                                                  (picker/picker-preview p
                                                                          (rt/data-snapshot session-id [:picker/items :picker/state])
                                                                          selection)))]
      ;; Called AFTER fzf has fully exited so println output isn't wiped
      (picker/execute p state)
      (shutdown-agents))
    (catch Exception e
      (println (str "Error: " (ex-message e)))
      (.printStackTrace e)
      (System/exit 1))))
