(ns piquine.statecharts
  "Thin wrapper around com.fulcrologic/statecharts: session bookkeeping,
  start/stop, and a synchronous send-and-await helper that watches the
  working-memory atom for state changes."
  (:require
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.environment :as env]
   [com.fulcrologic.statecharts.event-queue.core-async-event-loop :as loop]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.runtime :as rt]
   [com.fulcrologic.statecharts.simple :as simple]
   [taoensso.timbre :as log]))

(def sessions (atom {}))

(defn create-env [wmem]
  (simple/simple-env
   {::sc/working-memory-store (reify sp/WorkingMemoryStore
                                (get-working-memory [_ _ _] @wmem)
                                (save-working-memory! [_ _ _ m] (reset! wmem m)))
    ::sc/process-transactions! (fn [processed-env component transactions]
                                 (log/warn "Fulcro transactions ignored in CLI environment."
                                           {:component component :transactions transactions})
                                 processed-env)}))

(defn start-new-sc!
  [session-id chart-key chart]
  (let [wmem (atom {})
        env  (create-env wmem)]
    (simple/register! env chart-key chart)
    (let [running? (loop/run-event-loop! env 100)]
      (simple/start! env chart-key session-id)
      (swap! sessions assoc session-id {:env env :running? running? :wmem wmem}))))

(defn send-event!
  ([session-id event] (send-event! session-id event nil))
  ([session-id event data]
   (let [{:keys [env]} (get @sessions session-id)]
     (when env
       (simple/send! env {:target session-id :event event :data data})))))

(defn send-event-and-await-completion!
  "Send an event and wait for the next state change in the working memory,
  or until timeout-ms elapses. Returns true on state change, false on timeout."
  ([session-id event data timeout-ms]
   (let [session-info (get @sessions session-id)]
     (if-not session-info
       (do (println "Error: No session found for ID" session-id) false)
       (let [wmem (:wmem session-info)
             p    (promise)
             wk   (keyword (gensym "await-watch-"))]
         (add-watch wmem wk
                    (fn [k a old new]
                      (remove-watch a k)
                      (when (not= old new)
                        (deliver p ::event-processed))))
         (send-event! session-id event data)
         (let [result (deref p timeout-ms ::timeout)]
           (if (= result ::timeout)
             (do (remove-watch wmem wk) false)
             true))))))
  ([session-id event timeout-ms]
   (send-event-and-await-completion! session-id event nil timeout-ms)))

(defn stop-sc!
  [session-id]
  (let [{:keys [running?]} (get @sessions session-id)]
    (when running? (reset! running? false))))

(defn get-processing-env [sessions session-id]
  (let [sys-env (:env (get @sessions session-id))]
    (rt/processing-env sys-env session-id)))
