# Changelog

All notable changes to piquine are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `piquine.picker` — Picker protocol (`get-items`, `format-item`, `preview`,
  `multi-select?`, `handle-selection`, `execute`) and `picker->statechart`
  constructor.
- `piquine.fzf.runtime` — single-entry `run!` that drives any statechart
  satisfying the fzf contract (`:fzf/items` + `:fzf/multi?` data-model keys;
  `:event/select`/`:event/done`/`:event/abort` events; a `(final ...)`
  state).
- `piquine.statecharts` — thin Fulcro statecharts wrapper (session
  bookkeeping, start/stop, synchronous send-and-await).
- `piquine.fzf.client` — minimal fzf `--listen` HTTP API helpers.
- Three example pickers: command-menu (two-level), stack (multi-select
  across siblings), nested (three-level with ROT13 preview).
- `examples/swapi/` — non-trivial subproject: interactive GraphQL query
  builder for the SWAPI endpoint, using Pathom3 schema introspection. The
  use case that motivated piquine's predecessor, PhuzQL.
- Documentation in `docs/PICKERS.md`.
