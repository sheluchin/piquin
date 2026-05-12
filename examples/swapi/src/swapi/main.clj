(ns swapi.main
  (:require
   [piquine.fzf.client :as fzf-client]
   [piquine.fzf.runtime :as rt]
   [piquine.picker :as picker]
   [piquine.statecharts :as pqsc]
   [swapi.picker :as sp]
   [taoensso.timbre :as log]))

(log/set-min-level! :fatal)

(defn- commit-marks-then-done!
  "Ctrl-D handler: commits any tab-marked fields as leaves at the current
  path before terminating. Uses only :selected (not :current) so a cursor
  hovering on a non-leaf field doesn't accidentally drill in."
  [session-id]
  (let [fzf-s  (try (fzf-client/get-fzf-state) (catch Exception _ nil))
        marked (when fzf-s (mapv :text (:selected fzf-s)))]
    (when (seq marked)
      (pqsc/send-event-and-await-completion!
       session-id :event/select {:selection marked} 1000))
    (rt/send-bare! session-id :event/done)))

(defn -main [& _args]
  (log/set-min-level! :fatal)
  (try
    (let [p     (sp/create-swapi-picker)
          chart (picker/picker->statechart p {:path [] :query-tree {}})
          {:keys [state]} (rt/run! chart
                                    :session-id :swapi
                                    :header "Enter: drill / accumulate marks   Tab: mark   Ctrl-D: commit marks and finish"
                                    :keybindings {"ctrl-d" commit-marks-then-done!}
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
