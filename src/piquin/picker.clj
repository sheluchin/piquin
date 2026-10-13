(ns piquin.picker
  "Picker protocol and its statechart constructor.

  A Picker describes a stateful interactive selection. The protocol's job is to
  fill in the slots of a small template statechart that the runtime drives.

  Implement six methods:
    (get-items [this state])           -> sequence of raw items
    (format-item [this item])          -> string for fzf display
    (preview [this selected-items state]) -> string for the preview pane
    (multi-select? [this])             -> boolean
    (handle-selection [this items state]) -> {:state new-state :done? bool}
    (execute [this state])             -> final result (the run! return value)

  Call (picker->statechart picker initial-state) to get a chart. Pass that
  chart to piquin.fzf.runtime/run! along with any keybindings.

  When a picker outgrows this template (multi-step wizards, async loading,
  parallel selection regions), skip the protocol and hand-roll a statechart
  that satisfies the runtime contract:
    data-model keys :fzf/items, :fzf/multi?
    events :event/select {:selection [...]} , :event/done, :event/abort
    a (final ...) state with done-data producing the result

  Full guide: docs/PICKERS.md"
  (:require
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [data-model done-data final
                                                 script state transition]]))

(defprotocol Picker
  (get-items       [this state])
  (format-item     [this item])
  (preview         [this selected-items state])
  (multi-select?   [this])
  (handle-selection [this selected-items state])
  (execute         [this state]))

;; ---------------------------------------------------------------------------
;; Selection plumbing
;; ---------------------------------------------------------------------------

(defn- items->lookup
  "Map formatted-string -> raw-item, so we can recover raw items from fzf output."
  [picker raw-items]
  (zipmap (map #(format-item picker %) raw-items) raw-items))

(defn- parse-selections
  "Map a sequence of formatted strings (as returned by fzf) back to raw items.
  Falls through to the string if no raw item matches."
  [picker raw-items strings]
  (let [lookup (items->lookup picker raw-items)]
    (mapv #(get lookup % %) strings)))

;; ---------------------------------------------------------------------------
;; Statechart template
;; ---------------------------------------------------------------------------

(defn- compute-view
  "Re-derive the view (raw + formatted items) from picker state."
  [picker state]
  (let [raw (vec (get-items picker state))]
    {:picker/items raw
     :fzf/items   (mapv #(format-item picker %) raw)}))

(defn- H-handle-selection [picker]
  (fn [_env data]
    (let [strings    (get-in data [:_event :data :selection])
          raw-items  (:picker/items data)
          state      (:picker/state data)
          selection  (parse-selections picker raw-items strings)
          {new-state :state done? :done?} (handle-selection picker selection state)
          view       (compute-view picker new-state)]
      [(ops/assign :picker/state new-state)
       (ops/assign :picker/done? (boolean done?))
       (ops/assign :picker/items (:picker/items view))
       (ops/assign :fzf/items   (:fzf/items view))])))

(defn- C-done? [_env data] (boolean (:picker/done? data)))
(defn- C-not-done? [_env data] (not (boolean (:picker/done? data))))

(defn picker->statechart
  "Build a statechart that runs `picker` starting from `initial-state` (default {}).

  The chart terminates by entering :state/final. Its done-data exposes
  {:state picker-state} -- the CLI/runtime is expected to call `execute`
  on this state *after* fzf has fully exited so its println output isn't
  wiped by fzf's screen-clear on exit."
  ([picker]
   (picker->statechart picker {}))
  ([picker initial-state]
   (let [view (compute-view picker initial-state)]
     (statechart
      {:id ::picker :initial :state/ready :predictable-ids? true}
      (data-model
       {:expr {:picker/state   initial-state
               :picker/items   (:picker/items view)
               :picker/done?   false
               :fzf/items      (:fzf/items view)
               :fzf/multi?     (boolean (multi-select? picker))}})

      (state {:id :state/ready}
             (transition {:event :event/select :target :state/handle}
                         (script {:expr (H-handle-selection picker)}))
             (transition {:event :event/done :target :state/final})
             (transition {:event :event/abort :target :state/final}))

      (state {:id :state/handle}
             (transition {:cond C-done?     :target :state/final})
             (transition {:cond C-not-done? :target :state/ready}))

      (final {:id :state/final}
             (done-data {:expr (fn [_env data] {:state (:picker/state data)})}))))))

;; ---------------------------------------------------------------------------
;; Picker accessors for runtime callbacks
;; ---------------------------------------------------------------------------

(defn picker-preview
  "Helper used by the runtime to render the preview pane.
  Takes the picker instance, the current data-model snapshot, and the fzf
  selection (list of formatted strings); returns the preview string."
  [picker data fzf-strings]
  (let [raw-items (:picker/items data)
        state     (:picker/state data)
        selection (parse-selections picker raw-items fzf-strings)]
    (preview picker selection state)))
