# piquin

A small Clojure/Babashka framework for building interactive `fzf`-driven
selectors ("pickers"). Define a picker by implementing a six-method protocol;
the runtime turns it into a statechart and drives a long-lived fzf process
with it. Multi-select, nested navigation, preview pane, and selection
accumulation come for free.

## Status

Pre-1.0. The protocol is small and the API is unlikely to change much, but
namespaces and option names may still shift.

## Requirements

- [Babashka](https://babashka.org/) (`bb`) — or any Clojure runtime
- [`fzf`](https://github.com/junegunn/fzf) ≥ 0.39 on `PATH`

## Troubleshooting

> `failed to listen on localhost:9878`

A previous run left an orphaned fzf process holding the port. Kill it:

```bash
pkill -f "fzf --listen"
```

This can happen when the parent `bb` process is killed without letting it
exit cleanly (closing the terminal mid-run, `pkill bb`, etc.).

## Quick start

A complete picker in ~15 lines:

```clojure
(ns my.color-picker
  (:require [piquin.picker :as picker]
            [piquin.fzf.runtime :as rt]))

(defrecord ColorPicker []
  picker/Picker
  (get-items       [_ _state]   ["red" "green" "blue"])
  (format-item     [_ item]     item)
  (preview         [_ sel _]    (str "Hovering: " (first sel)))
  (multi-select?   [_]          false)
  (handle-selection [_ items state]
    {:state (assoc state :chosen (first items))
     :done? true})
  (execute [_ state]
    (println "You picked:" (:chosen state))))

(defn -main [& _]
  (let [p     (->ColorPicker)
        chart (picker/picker->statechart p {})
        {:keys [state]} (rt/run! chart)]
    (picker/execute p state)))
```

## Try the bundled examples

```bash
bb command-menu   # two-level menu — pick a command, see its result
bb stack          # multi-select across sibling categories
bb nested         # three-level hierarchy with ROT13 preview
```

A larger, real-world example builds GraphQL queries against the Star Wars
API and is kept as a subproject with its own deps (Pathom3) in
[`examples/swapi`](examples/swapi):

```bash
cd examples/swapi && bb swapi
```

## The protocol at a glance

| Method                                  | Returns                          | Called when                                |
| --------------------------------------- | -------------------------------- | ------------------------------------------ |
| `(get-items this state)`                | sequence of raw items            | every time state changes, to refresh fzf   |
| `(format-item this item)`               | display string                   | once per item before it's shown in fzf     |
| `(preview this selected-items state)`   | string                           | fzf's preview pane refreshes               |
| `(multi-select? this)`                  | boolean                          | once at chart start                        |
| `(handle-selection this items state)`   | `{:state new-state :done? bool}` | user presses Enter                         |
| `(execute this state)`                  | anything                         | by the CLI, *after* fzf has fully exited   |

`handle-selection` is the whole picker. It's a pure transition function:
given selected items and the current state, return the next state plus a
`:done?` flag. Navigation, accumulation, and termination are all just state
updates.

## How it works

`(picker->statechart your-picker initial-state)` returns a chart with three
states (`:state/ready`, `:state/handle`, `:state/final`). The chart's
data-model exposes `:fzf/items` and `:fzf/multi?`. `piquin.fzf.runtime/run!`
launches a single long-lived fzf process and wires fzf keystrokes to
in-process callbacks that send chart events. Each Enter calls your
`handle-selection`; when `:done? true` or the user hits Ctrl-D, the chart
reaches `:state/final`. After fzf exits, the CLI calls
`(picker/execute your-picker final-state)`.

There's one code path through the system regardless of what your picker
does. When the protocol's not flexible enough (multi-step wizards, parallel
selection, async loading), drop the protocol and hand-roll a chart that
satisfies the same data-model + event contract — same runtime drives it.

## Installation

`deps.edn`:

```clojure
io.github.sheluchin/piquin
{:git/url "https://github.com/sheluchin/piquin"
 :git/sha "..."}
```

`bb.edn` (Babashka): same, with the deps inline under `:deps`.

## Documentation

- **[docs/PICKERS.md](docs/PICKERS.md)** — full guide: protocol details, the
  `handle-selection` transition function, CLI wiring, recipes for
  hierarchical navigation / multi-select / accumulation / custom keys /
  custom preview, and the runtime contract for hand-rolled charts.

## License

MIT — see [LICENSE](LICENSE).
