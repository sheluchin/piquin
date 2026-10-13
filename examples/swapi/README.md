# SWAPI GraphQL picker

An interactive GraphQL query builder for the [SWAPI GraphQL
endpoint](https://swapi-graphql.netlify.app/graphql), built on
[piquin](../..).

This is the use case that motivated piquin in the first place (the original
[PhuzQL](https://github.com/sheluchin/phuzql)). Pathom3 introspects the SWAPI
schema; you navigate it with `fzf`, marking fields as leaves or drilling into
them; the preview pane shows the assembled EQL and runs the query live as you
build.

## Run

```bash
bb swapi
```

## Controls

- **Enter** on a single field: drill into it (becomes a join in the query)
- **Tab + Enter** on multiple fields: add them as leaves at the current path
- **`← back`**: go up one level
- **`✓ done`** (or **Ctrl-D**): finish; the final query and its result are
  printed

## Try

1. Drill into `swapi.Root/allFilms`
2. Drill into `swapi.FilmsConnection/films`
3. Tab-mark `swapi.Film/title` and `swapi.Film/director`, then Enter
4. Ctrl-D

The preview should show the query and the six Star Wars films with their
directors. The same query is printed on exit.

## Code layout

- `src/swapi/api.clj`    — Pathom3 connector + `reachable-attributes` /
                           `run-query` helpers
- `src/swapi/picker.clj` — the `SwapiPicker` defrecord (the only piquin-
                           specific code)
- `src/swapi/main.clj`   — CLI entry point: builds the chart, hands it to
                           `piquin.fzf.runtime/run!`, prints the result

The `SwapiPicker` is a single defrecord that:
- maintains `:path` (where we are in the tree) and `:query-tree` (a nested
  map being built) in its state
- in `handle-selection`, routes single-vs-multi selections into drill / leaf
  behaviour
- in `preview`, assembles the live EQL and runs it through pathom every
  time the cursor moves
