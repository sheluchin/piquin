# Building Pickers

A picker is an interactive fzf-driven selector. You define one by implementing
the `Picker` protocol; the runtime turns it into a statechart and drives fzf
with it. There is one code path through the system regardless of what your
picker does.

## Architecture in one paragraph

`(picker/picker->statechart your-picker initial-state)` returns a chart with
three states (`:state/ready`, `:state/handle`, `:state/final`). The chart's
data-model exposes `:fzf/items` and `:fzf/multi?`. The runtime
(`fzf.runtime/run!`) launches a single long-lived fzf process and wires fzf
keystrokes to in-process callbacks that send chart events. Each Enter calls
your `handle-selection` to compute the next state; the chart reaches
`:state/final` when you set `:done? true` or the user hits Ctrl-D. After fzf
exits, the CLI calls `(picker/execute your-picker final-state)`.

## Quick start

A trivial picker that lets you pick one of three colors and prints it:

```clojure
(ns my.color-picker
  (:require [piquine.picker :as picker]
            [piquine.fzf.runtime :as rt]))

(defrecord ColorPicker []
  picker/Picker
  (get-items       [_ _state]   ["red" "green" "blue"])
  (format-item     [_ item]     item)
  (preview         [_ sel _]    (str "You're hovering on: " (first sel)))
  (multi-select?   [_]          false)
  (handle-selection [_ items state]
    {:state (assoc state :chosen (first items))
     :done? true})              ; one pick -> we're done
  (execute [_ state]
    (println "You picked:" (:chosen state))))

(defn -main [& _]
  (let [p     (->ColorPicker)
        chart (picker/picker->statechart p {})
        {:keys [state]} (rt/run! chart)]
    (picker/execute p state)))
```

Add a task in `bb.edn` and run it. Every other picker is a variation on this
shape.

## The `Picker` protocol

| Method                                  | Returns                          | Called when                                |
| --------------------------------------- | -------------------------------- | ------------------------------------------ |
| `(get-items this state)`                | sequence of raw items            | every time state changes, to refresh fzf   |
| `(format-item this item)`               | display string                   | once per item before it's shown in fzf     |
| `(preview this selected-items state)`   | string                           | fzf's preview pane refreshes               |
| `(multi-select? this)`                  | boolean                          | once at chart start (decides fzf's `--multi`) |
| `(handle-selection this items state)`   | `{:state new-state :done? bool}` | user presses Enter                         |
| `(execute this state)`                  | anything                         | by the CLI, *after* fzf has exited         |

Items can be any Clojure values. `format-item` converts them to strings for
fzf; selections come back to `handle-selection` as the original raw values
(matched by formatted string). Most pickers just use strings throughout, in
which case `format-item` is `identity`.

## `handle-selection` is the whole picker

It's the transition function. Given the currently selected items and the
current state, return the next state and a `:done?` flag.

```clojure
(handle-selection [_ items state]
  {:state (... compute new state from items + old state ...)
   :done? false})        ; false -> stay in fzf with the new state
                          ; true  -> commit, execute, and exit
```

Navigation, accumulation, and termination are *all* just state updates:

- **Navigate "into" something**: store a deeper path/level in state. Your
  `get-items` reads that and returns the children. fzf reloads with them.
- **Navigate "back"**: pop the path/level. fzf reloads with the parent items.
- **Accumulate selections**: stash them somewhere in state under a key your
  `execute` knows about, e.g. `(:collected state)`.
- **Done**: set `:done? true`. The chart hits final, the CLI calls `execute`.

## CLI wiring

The boilerplate is the same for every picker:

```clojure
(ns my.cli
  (:require [piquine.picker :as picker]
            [piquine.fzf.runtime :as rt]
            [taoensso.timbre :as log]))

(log/set-min-level! :fatal)   ; silence the statechart's debug logging

(defn -main [& _]
  (let [p     (my.ns/create-picker)
        chart (picker/picker->statechart p {... initial state ...})
        {:keys [state]} (rt/run! chart
                                  :session-id :my-picker
                                  :header     "Enter: pick   Ctrl-D: done"
                                  :preview-fn (fn [session-id selection]
                                                (picker/picker-preview p
                                                  (rt/data-snapshot session-id [:picker/items :picker/state])
                                                  selection)))]
    ;; AFTER fzf has fully exited (so any println in execute survives)
    (picker/execute p state)
    (shutdown-agents)))
```

Two things worth flagging:

1. **Always call `picker/execute` from the CLI after `run!` returns**, not from
   inside the chart. fzf clears the screen when it exits and would wipe any
   `println` you do mid-run. The chart's `:state/final` only stores the
   final state; the CLI does the side effects.
2. **`picker-preview` is a helper** that does the formatted-string -> raw-item
   mapping for you. If you skip it, your `preview-fn` will receive whatever
   strings fzf has on screen.

## Worked examples

The examples are short. Read them in this order:

- **[command_menu.clj](../src/piquine/examples/command_menu.clj)** — two-level menu (root → submenu → terminate). Simplest case with
  navigation and a `:done? true` exit.
- **[stack_picker.clj](../src/piquine/examples/stack_picker.clj)** — multi-select across sibling categories. Demonstrates accumulating
  selections in state, with live count badges in the root view.
- **[nested_picker.clj](../src/piquine/examples/nested_picker.clj)** — three-level hierarchy. Shows a `← back` sentinel item for
  navigation up; preview pane shows ROT13 of every collected item.

## Common recipes

### Hierarchical navigation

Track the path in state. `get-items` looks up children at that path.
`handle-selection` either pushes (navigate in) or pops (navigate back).

```clojure
(get-items [_ state]
  (let [path (:path state [])]
    (if (empty? path)
      root-items
      (children-of (last path)))))

(handle-selection [_ items state]
  (let [item (first items)]
    {:done? false
     :state
     (cond
       (= item back-sentinel)  ; user picked "← back"
       (update state :path (comp vec butlast))

       (has-children? item)
       (update state :path conj item)

       :else
       (-> state
           (update :collected (fnil conj []) item)
           (update :path (comp vec butlast))))}))
```

### Multi-select that stays in fzf

Return `:done? false` and let `get-items` continue producing items.
`multi-select?` returns `true`, so fzf accepts Tab marks. The user pressing
Enter calls `handle-selection` with all marked items (plus the cursor item).
They press Ctrl-D when finished — the runtime maps that to `:event/abort`,
which terminates the chart and goes to `execute`.

### Accumulating across multiple selection rounds

Each Enter accumulates more into state without exiting:

```clojure
(handle-selection [_ items state]
  {:done? false
   :state (update state :collected (fnil into []) items)})
```

The user picks repeatedly; their selections pile up in `(:collected state)`.
Ctrl-D commits.

### Custom keybindings

Pass `:keybindings {fzf-key event-keyword}` to `run!`:

```clojure
(rt/run! chart
         :keybindings {"enter"   :event/select   ; default
                       "ctrl-d"  :event/done     ; default
                       "ctrl-h"  :event/go-back  ; your own event
                       })
```

For your own events, you'll need a hand-rolled chart (see below) that handles
them — the protocol-generated chart only knows about `:event/select`,
`:event/done`, and `:event/abort`. For most pickers a "go back" can be done
with a sentinel item plus a clause in `handle-selection`, no custom event
needed.

### Custom preview

Pass a `:preview-fn` to `run!`. It receives `[session-id selection-strings]`
and returns the preview string:

```clojure
:preview-fn (fn [session-id sel-strings]
              (let [{:keys [picker/state]} (rt/data-snapshot session-id [:picker/state])]
                (str "Hovering: " (first sel-strings) "\nIn state: " (:level state))))
```

If you use `picker/picker-preview` it'll just delegate to your protocol's
`preview` method.

## Skipping the protocol

The runtime doesn't care how the chart was built. If your picker needs
something outside the template (multi-step wizards, parallel selection
regions, async loaded items, timeouts), write a statechart that satisfies the
contract:

| In data-model     | Used for                          |
| ----------------- | --------------------------------- |
| `:fzf/items`      | vector of strings shown in fzf    |
| `:fzf/multi?`     | passed to fzf as `--multi`        |

| Event              | Sent on              | Payload                  |
| ------------------ | -------------------- | ------------------------ |
| `:event/select`    | Enter                | `{:selection [...]}`     |
| `:event/done`      | Ctrl-D               | `{}`                     |
| `:event/abort`     | Esc                  | `{}`                     |

Any `(final ...)` state ends the run. After fzf exits, `(rt/data-get
session-id :picker/state)` (or whatever key you wrote your result to) is
available.

Same `rt/run!` entry point, same fzf wiring, just a different chart.

## Reference

- `piquine.picker` — protocol + `picker->statechart` + `picker-preview` helper
- `piquine.fzf.runtime` — `run!`, `data-get`, `data-snapshot`
- `piquine.statecharts` — Fulcro statechart wrapper
- `piquine.fzf.client` — fzf HTTP API helpers (rarely needed in pickers)
