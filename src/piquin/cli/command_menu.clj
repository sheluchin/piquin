(ns piquin.cli.command-menu
  (:require
   [piquin.examples.command-menu :as cmd-menu]
   [piquin.fzf.runtime :as rt]
   [piquin.picker :as picker]
   [taoensso.timbre :as log]))

(log/set-min-level! :fatal)

(defn -main [& _args]
  (log/set-min-level! :fatal)
  (try
    (let [p     (cmd-menu/create-command-menu-picker)
          chart (picker/picker->statechart p {:level :root})
          {:keys [state]} (rt/run! chart
                                    :session-id :command-menu
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
