(ns piquine.cli.stack-picker
  (:require
   [piquine.examples.stack-picker :as stack]
   [piquine.fzf.runtime :as rt]
   [piquine.picker :as picker]
   [taoensso.timbre :as log]))

(log/set-min-level! :fatal)

(defn -main [& _args]
  (log/set-min-level! :fatal)
  (try
    (let [p     (stack/create-stack-picker)
          chart (picker/picker->statechart p {:level :root :selected {}})
          {:keys [state]} (rt/run! chart
                                    :session-id :stack-picker
                                    :header "Enter: select/navigate  Tab: mark  Ctrl-D: done"
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
