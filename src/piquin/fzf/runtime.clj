(ns piquin.fzf.runtime
  "Drives any statechart satisfying the fzf contract:

    Data-model keys
      :fzf/items     - vector of strings to display in fzf
      :fzf/multi?    - boolean, whether fzf runs with --multi

    Events
      :event/select {:selection [...]}  - sent on Enter (and any custom binding
                                          you configure with this event name)
      :event/done                       - sent on Ctrl-D
      :event/abort                      - sent on Esc

    Terminal states
      Any (final ...) state. Its done-data is returned as the run! result.

  Custom keybindings: pass {:keybindings {KEY EVENT}} where KEY is an fzf key
  string and EVENT is a keyword. For :event/select the runtime fills in the
  current fzf selection automatically; other events get an empty payload."
  (:require
   [piquin.fzf.client :as fzf-client]
   [piquin.statecharts :as pqsc]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.protocols :as sp]
   [fzf.core :refer [fzf]]))

;; ---------------------------------------------------------------------------
;; Data-model access
;; ---------------------------------------------------------------------------

(defn- proc-env [session-id]
  (pqsc/get-processing-env pqsc/sessions session-id))

(defn data-get
  "Read a single key from the chart's data-model."
  [session-id k]
  (let [env (proc-env session-id)
        dm  (::sc/data-model env)]
    (sp/get-at dm env k)))

(defn data-snapshot
  "Read several keys from the chart's data-model as a map."
  [session-id ks]
  (let [env (proc-env session-id)
        dm  (::sc/data-model env)]
    (zipmap ks (map #(sp/get-at dm env %) ks))))

;; ---------------------------------------------------------------------------
;; Final-state detection
;;
;; When a Fulcrologic statechart enters a final state, its working-memory
;; flips ::sc/running? to false. That's our termination signal.
;; ---------------------------------------------------------------------------

(defn- wmem-atom [session-id]
  (:wmem (get @pqsc/sessions session-id)))

(defn- done? [session-id]
  (false? (-> (wmem-atom session-id) deref ::sc/running?)))

(defn- wait-for-final
  "Block until the chart's wmem flips ::sc/running? to false. Returns the
  current picker state captured at that moment."
  [session-id]
  (let [wmem (wmem-atom session-id)
        p    (promise)
        wk   (keyword (gensym "final-watch-"))]
    (if (done? session-id)
      (deliver p :done)
      (do
        (add-watch wmem wk
                   (fn [k a _o new]
                     (when (false? (::sc/running? new))
                       (remove-watch a k)
                       (deliver p :done))))
        @p))
    {:state (data-get session-id :picker/state)}))

;; ---------------------------------------------------------------------------
;; Event sending
;; ---------------------------------------------------------------------------

(defn send-with-selection!
  "Send :event/select with the current fzf selection (current + tab-marked)."
  [session-id]
  (let [fzf-s     (try (fzf-client/get-fzf-state) (catch Exception _ nil))
        current   (some-> fzf-s :current :text)
        marked    (when fzf-s (map :text (:selected fzf-s)))
        selection (vec (remove nil? (distinct (cons current marked))))]
    (when (seq selection)
      (pqsc/send-event-and-await-completion!
       session-id :event/select {:selection selection} 1000))))

(defn send-bare!
  "Send a custom event with no payload."
  [session-id event]
  (pqsc/send-event-and-await-completion! session-id event {} 1000))

;; ---------------------------------------------------------------------------
;; Keybindings -> fzf in-process callbacks
;; ---------------------------------------------------------------------------

(defn- key-handler
  "Build the in-process fn for one keybinding. `ev-or-fn` is either an event
  keyword (the framework sends it; :event/select also fills in the fzf
  selection) or a function (fn [session-id]) for custom multi-step behavior.
  The handler returns the updated :fzf/items so fzf reloads."
  [session-id ev-or-fn]
  (fn [_query & _selections]
    (cond
      (fn? ev-or-fn)              (ev-or-fn session-id)
      (= :event/select ev-or-fn)  (send-with-selection! session-id)
      :else                        (send-bare! session-id ev-or-fn))
    (data-get session-id :fzf/items)))

(def ^:private default-keybindings
  {"enter"  :event/select
   "ctrl-d" :event/done
   "esc"    :event/abort})

(defn- compile-bindings [session-id keybindings]
  (->> keybindings
       (map (fn [[k ev]]
              [k [{:action-name   "reload"
                   :in-process-fn (key-handler session-id ev)}]]))
       (into {})))

;; ---------------------------------------------------------------------------
;; Preview pane
;; ---------------------------------------------------------------------------

(defn- compile-preview
  "If a preview-fn was provided, wrap it so fzf invokes it with the current
  selection (current + tab-marked) on every preview refresh."
  [session-id preview-fn]
  (when preview-fn
    (fn [_selections-from-fzf]
      (let [fzf-s     (try (fzf-client/get-fzf-state) (catch Exception _ nil))
            current   (some-> fzf-s :current :text)
            marked    (when fzf-s (map :text (:selected fzf-s)))
            selection (vec (remove nil? (distinct (cons current marked))))]
        (preview-fn session-id selection)))))

;; ---------------------------------------------------------------------------
;; Public: run!
;; ---------------------------------------------------------------------------

(defn run!
  "Run a chart against fzf. Options:
    :session-id  - chart session id (default: gensym)
    :keybindings - {fzf-key event-keyword}; merged on top of default-keybindings
    :preview-fn  - (fn [session-id selection]) -> string for the preview pane
    :header      - fzf header string

  Returns {:state picker-state}. fzf is launched in the calling process's
  terminal; the call blocks until the chart enters a final state."
  [chart & {:keys [session-id keybindings preview-fn header]
            :or   {session-id (keyword (gensym "picker-"))}}]
  (pqsc/start-new-sc! session-id session-id chart)
  (let [port        (fzf-client/free-port)
        _           (fzf-client/set-listen-port! port)
        bindings    (merge default-keybindings (or keybindings {}))
        items       (data-get session-id :fzf/items)
        multi?      (boolean (data-get session-id :fzf/multi?))
        fzf-opts    (cond-> {:multi multi?
                             :command-bindings (compile-bindings session-id bindings)
                             :additional-raw-args [(str "--listen=" port)]}
                      header     (assoc :header {:header-str header})
                      preview-fn (assoc :preview-fn (compile-preview session-id preview-fn)))
        fzf-future  (future (fzf fzf-opts items))]
    (try
      (let [result (wait-for-final session-id)]
        ;; Tell fzf to exit so its future completes
        (fzf-client/send-fzf-command port "abort")
        (try @fzf-future (catch Exception _ nil))
        result)
      (finally
        (pqsc/stop-sc! session-id)))))
