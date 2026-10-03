# Implementation Plan — SSSF Visualizer Waterfall Timeline in Cockpit Factory

## Overview
Refactor the run timeline component in the vanilla ESM Factory Cockpit (`factory/dashboard/js/components/temporal-lanes.mjs`) and its styling (`factory/dashboard/css/dockyard.css`) to faithfully reproduce the SSSF "SessionTrace" visualizer (a horizontal multi-lane waterfall chart). Ensure all non-available metrics in the Factory projection render explicitly as literal `"-"`.

## Targeted Files
1. `factory/dashboard/js/components/temporal-lanes.mjs`:
   - Replace equal-width stacked block layout (`1/total`) with real-time horizontal placement (`startRatio` / `leftPct`, `widthRatio` / `widthPct`).
   - Implement minimal block width floor (`3%`) for short steps and non-overlapping sequential positioning.
   - Implement lane grouping: `engineer` (amber `#e8b64a`), `code` (cyan `#5ad2dd` - if present), and one lane per distinct `agent` (varying colors: `--purple`, `--violet`, `--blue`).
   - Render `run-strip` header: title, status chip, start date, workflow type, and statistics chips (`COST: -`, `RUNTIME: <calculated>`, `TOKENS: -`, `READ: -`, `WRITTEN: -`).
   - Render lane left label column (~280px) with icons (`engineer`: 👤, `code`: 💻, `agent`: 🤖), plus agent submeta: `Model: -` and `Context: -` / empty bar.
   - Render time axis with relative tick marks (`0s`, `30s`, `1m`, ...).
   - Render blocks with status glyphs (`✓`/`✗`/`●`/`○`), phase name, and duration chip. Pending steps rendered as dashed "queued" blocks.
2. `factory/dashboard/css/dockyard.css`:
   - Add/update visual styles for `.run-strip`, `.waterfall-container`, `.waterfall-axis`, `.waterfall-lane`, `.lane-label-col`, `.lane-submeta`, `.lane-track`, and `.waterfall-block`.
   - Use CSS variables (`--amber: #e8b64a`, `--cyan: #5ad2dd`, `--green: #4ade80`, `--red: #ff6f67`, `--blue: #6cb6ff`, `--mono`).
3. `factory/dashboard/js/views/run-detail.mjs`:
   - Update `renderHeader` / timeline embedding if necessary to integrate `renderRunStrip` and `renderTemporalLanes` smoothly while retaining step click selection and phase panel loading.
4. `factory/dashboard/js/components/temporal-lanes.test.mjs`:
   - Vanilla Node.js unit tests (`node --test factory/dashboard/js/components/temporal-lanes.test.mjs`) covering:
     - Actor classification (`engineer`, `code`, `agent`).
     - Real-time layout calculations, minimal width floor, and non-overlapping sequential positions.
     - Presence of literal `"-"` for missing metrics (`cost`, `tokens`, `read`, `written`, agent `model`, `% context`).

## Verification Steps
1. Run unit test suite:
   ```bash
   node --test factory/dashboard/js/components/temporal-lanes.test.mjs
   ```
2. Run standard project tests / verification:
   ```bash
   pnpm tsx libs/integration/src/lib/factory.tools.node-test.ts
   ```
3. Manual verification:
   - Start or serve Factory Cockpit at `http://127.0.0.1:8141/`.
   - Open a workflow run (e.g. `test-run-1`).
   - Verify horizontal multi-lane waterfall with `run-strip`, `engineer`, `code`, and `agent` swimlanes.
   - Verify block positioning according to timestamps/durations and click selection behavior.
   - Confirm literal `"-"` displays for `COST`, `TOKENS`, `READ`, `WRITTEN`, `Model`, and `Context`.
