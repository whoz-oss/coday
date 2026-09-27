# Plan — Correctifs Cockpit Factory (Run Detail Timeline & List Dockyard Copy)

## Overview

This plan details two specific UI/UX updates to the vanilla ESM Factory Cockpit dashboard served at `/factory/dashboard/` by `factory-service` (`:8141`).

1. **Fix 1 (Run Detail View)**: Remove the redundant timeline block (`ACTEUR · TEMPS` Gantt section) in `factory/dashboard/js/views/run-detail.mjs` and its component `factory/dashboard/js/components/gantt.mjs`. Keep **ONLY** the SSSF waterfall timeline at the top (`buildWaterfallLayout` / `renderWaterfallTimeline` in `temporal-lanes.mjs`) and the phase detail inspector (`renderPhasePanel` in `phase-panel.mjs`).
2. **Fix 2 (Runs List View)**: Redesign the workflow list view (`factory/dashboard/js/views/projection.mjs` and `factory/dashboard/js/components/workflow-card.mjs` + `dockyard.css`) to be an **exact visual replica** of the "Coday Dockyard" sandboxes dashboard style while preserving existing functionality (active/removed tabs, restore/remove/purge lifecycle actions, navigation to detail timeline).

Zero build, zero dependencies, vanilla ESM.

---

## Technical Analysis & Target Changes

### Fix 1: Remove Duplicate Timeline in Run Detail View

#### Analysis
In `factory/dashboard/js/views/run-detail.mjs`:
- Currently, `mount()` calls `renderSwimlanes(lanes)` (which uses `buildWaterfallLayout` and `renderWaterfallTimeline` from `temporal-lanes.mjs`), then calls `renderGantt()` from `gantt.mjs`, then `renderPhasePanel()`, and finally `renderMetricsStrip()`.
- `renderGantt()` produces a panel with header `"ACTEUR · TEMPS"`.
- When no step is selected, `renderPhasePanel()` shows `"Sélectionner une étape dans la timeline."`.
- Therefore, `renderGantt` in `run-detail.mjs` is the duplicate timeline block.

#### Changes Needed
1. In `factory/dashboard/js/views/run-detail.mjs`:
   - Remove import of `renderGantt` (keep `normalizeSteps` if needed for step resolution, or import directly).
   - In `render()`, remove the `${gantt}` interpolation so the structure is strictly:
     Header -> Waterfall Swimlanes (SSSF) -> Phase Inspector (`renderPhasePanel`) -> Operational Metrics Strip (`renderMetricsStrip`).
2. In `factory/dashboard/js/components/gantt.mjs`:
   - Clean up unused exports or retain low-level helpers if imported elsewhere. (Audit shows `renderGantt` was only called in `run-detail.mjs`).
3. Check `phase-panel.mjs`:
   - Keep the phase panel default placeholder `"Sélectionner une étape dans la timeline."` intact as instructed ("Le panneau de détail d'une phase (au clic sur un bloc) reste").

---

### Fix 2: Redesign Runs List Page to Coday Dockyard Style

#### Analysis
In `factory/dashboard/`:
- `projection.mjs` manages list mode (`active` vs `removed`), grouping by case/ticket, and rendering `.projection-view` / `.projection-groups`.
- `workflow-card.mjs` renders each snapshot as an HTML card (`.workflow-card`).
- `dockyard.css` contains theme CSS variables and component styles.

#### Design & Structure Specifications for Dockyard Cards

1. **Palette Variables in `dockyard.css`**:
   Ensure all specified CSS variables are defined in `:root`:
   - `--bg: #06080f;`
   - `--panel: #0d1119;`
   - `--panel-2: #131a26;`
   - `--panel-3: #0a0e16;`
   - `--border: #232c3d;`
   - `--border-soft: #222b3d;`
   - `--text: #f2f5fa;`
   - `--dim: #aabdd5;`
   - `--faint: #8b9cb6;`
   - `--green: #4ade80;`
   - `--red: #ff6f67;`
   - `--blue: #6cb6ff;`
   - `--amber: #e8b64a;`
   - `--purple: #c89bff;`
   - `--cyan: #5ad2dd;`
   - `--violet: #94a3ff;`
   - `--surface: linear-gradient(180deg, #10141f 0%, #0b0f18 100%);`
   - `--sans: 'Play', 'Helvetica Neue', system-ui, sans-serif;`
   - `--mono: ui-monospace, 'SF Mono', Menlo, Monaco, 'Roboto Mono', monospace;`

2. **Grid & Cards Structure**:
   - Container `.runs`: `display: grid; gap: 18px; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));`
   - Card `.card` (or `.workflow-card.card`):
     - `padding: 20px 22px; display: grid; gap: 14px; border: 1px solid var(--border-soft); border-radius: 16px; background: var(--surface); transition: border-color .18s, box-shadow .18s; cursor: pointer;`
     - `.card:hover`: `border-color: rgba(148, 163, 255, 0.45); box-shadow: 0 10px 34px rgba(148, 163, 255, 0.12);`
     - `.card.running`: `border-color: rgba(108, 182, 255, 0.6); box-shadow: 0 0 22px rgba(108, 182, 255, 0.16);`
     - `.card.fail`: `border-color: rgba(255, 111, 103, 0.6);`

3. **Card Header (`.card-head`)**:
   - `display: flex; align-items: center; gap: 12px; flex-wrap: wrap;`
   - `.run-id`: `font-family: var(--mono); font-size: 18px; font-weight: bold; color: var(--purple);`
   - Status chip `.chip`:
     - Status mappings:
       - `completed` / `pass` -> `.chip.success` with label `"réussi"` and green SVG checkmark.
       - `failed` / `cancelled` / `fail` -> `.chip.fail` with label `"échoué"` and red SVG cross.
       - `running` / `waiting_human` -> `.chip.running` with label `"en cours"` and blue spinning SVG / spinner.
       - `pending` / `ready` / `blocked` -> `.chip.queued` with dashed border and label `"en attente"` or `"bloqué"`.
     - Standard `.chip`: `display: inline-flex; align-items: center; gap: 7px; padding: 3px 13px 3px 10px; border-radius: 999px; border: 1px solid var(--border); font-size: 15px; color: var(--dim);`
     - Glow variants (`.chip.success`, `.chip.fail`, `.chip.running`, `.chip.queued`).
   - `.dots` (phase dots):
     - `display: inline-flex; gap: 5px; font-size: 15px;`
     - One dot per step colored by step state: `.d.success { color: var(--green); }`, `.d.fail { color: var(--red); }`, `.d.running { color: var(--blue); animation: pulse 1.2s infinite; }`, `.d.queued { color: var(--faint); }`.
     - Glyph: `◐` for `running`, `●` for others. `title` attribute on hover: `"nom : état"`.
   - Spacer: `flex: 1`
   - Right secondary info in `.faint` 14px (e.g., revision `r1` or creation date).

4. **Card Meta (`.card-meta`)**:
   - `display: flex; gap: 8px; flex-wrap: wrap; align-items: baseline; font-family: var(--mono); font-size: 14px; color: var(--cyan);`
   - Format: `projet › titre/branche · type` (derived from workflow snapshot / projection attributes, fallback gracefully).

5. **Stat Chips Row (`.stats-row`)**:
   - Class `.stat`: `display: inline-flex; align-items: center; gap: 7px; padding: 3px 12px; border: 1px solid var(--border-soft); border-radius: 999px; background: rgba(19, 26, 38, 0.6); font-size: 15px;`
   - `.stat svg`: `color: var(--faint)`
   - `.stat b`: `font-family: var(--mono); font-variant-numeric: tabular-nums; color: var(--text); font-weight: 400;`
   - 3 Stat chips:
     - **COST**: `$` icon, value `-`
     - **RUNTIME**: Clock icon, calculated duration from `startedAt` to `completedAt` (using `formatDuration` / `fmtDur`), or `-`
     - **TOKENS**: Token icon, value `-`

6. **Sub-cards for Steps / Sessions (`.sessions` / `.session`)**:
   - Below header/meta, display workflow steps in sub-cards matching Dockyard workflow/session list:
   - Container `.sessions`: `display: grid; gap: 8px;`
   - Sub-card `.session`: `display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 18px; padding: 14px 16px; border: 1px solid var(--border-soft); border-radius: 12px; background: rgba(10, 14, 22, 0.55);`
   - Sub-card left side:
     - `.s-id`: mono, `var(--purple)` (step ID or index)
     - Step name in `.s-adw`: mono, `var(--cyan)`
     - Step status chip
   - Sub-card right side:
     - Step stats row (`.stat` for step duration if available; cost/tokens as `-`)

7. **Card Actions (`.actions`)**:
   - `display: flex; gap: 8px; flex-wrap: wrap; padding-top: 14px; border-top: 1px solid var(--border-soft);`
   - Button styling: `background: rgba(19, 26, 38, 0.6); border: 1px solid var(--border); border-radius: 10px; padding: 7px 14px; font-size: 14px; color: var(--text);`
   - `.danger` style for remove/purge buttons: `color: var(--red); border-color: rgba(255, 111, 103, 0.35); background: rgba(255, 111, 103, 0.08);`
   - Preserve existing actions: `restore`, `remove`, `purge`, and detail navigation on card click.

8. **Animations**:
   - `@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }`
   - `@keyframes spin { to { transform: rotate(360deg); } }`

9. **Projection View Header & Tabs**:
   - Retain header title / Factory branding & active / removed tabs (`Actifs` / `Supprimés`). Ensure group summaries or layout wrap the `.runs` grid seamlessly.

---

## File Changes Summary

| File Path | Purpose of Modification |
| --- | --- |
| `factory/dashboard/js/views/run-detail.mjs` | Remove call to `renderGantt()` and import from `gantt.mjs`. Only keep SSSF waterfall timeline, phase inspector panel, and metrics strip. |
| `factory/dashboard/js/components/workflow-card.mjs` | Re-architect HTML output for `renderWorkflowCard()` to match exact Coday Dockyard card layout (`.card`, `.card-head`, `.card-meta`, `.chip` with inline SVGs, `.dots`, `.stat` row for COST/RUNTIME/TOKENS, `.sessions` / `.session` sub-cards for steps, `.actions`). |
| `factory/dashboard/js/views/projection.mjs` | Wrap grouped workflow nodes in `.runs` grid container with Dockyard styling. Ensure event handling for lifecycle buttons & card click detail navigation remains functional. |
| `factory/dashboard/css/dockyard.css` | Add/update CSS definitions for palette variables, `.runs`, `.card`, `.card-head`, `.card-meta`, `.chip` glowing variants, `.dots`, `.stat`, `.session`, `.s-id`, `.s-adw`, `.actions`, and keyframes `pulse`/`spin`. |

---

## Step-by-Step Implementation Steps

### Step 1: Remove Duplicate Timeline from Run Detail (`run-detail.mjs`)
1. Edit `factory/dashboard/js/views/run-detail.mjs`.
2. Remove `renderGantt` call in `render()`.
3. Update template to render:
   ```js
   container.innerHTML = `<div class="run-detail" data-run-detail="true">${renderHeader()}${renderSwimlanes(
     lanes
   )}${panel}${renderMetricsStrip(state.metrics)}</div>`
   ```
4. Verify step clicking in waterfall still targets `selectStep(stepId)` and opens `renderPhasePanel`.

### Step 2: Add Dockyard Palette & CSS Styles (`dockyard.css`)
1. Edit `factory/dashboard/css/dockyard.css`.
2. Define/verify all required variables in `:root`:
   `--bg`, `--panel`, `--panel-3`, `--border`, `--border-soft`, `--text`, `--dim`, `--faint`, `--green`, `--red`, `--blue`, `--amber`, `--purple`, `--cyan`, `--violet`, `--surface`, `--sans`, `--mono`.
3. Add class definitions for:
   - `.runs` (grid container)
   - `.card`, `.card.running`, `.card.fail`, `.card:hover`
   - `.card-head`, `.run-id`
   - `.chip` and status variants (`.chip.success`, `.chip.fail`, `.chip.running`, `.chip.queued`)
   - `.dots`, `.d`, `.d.success`, `.d.fail`, `.d.running`, `.d.queued`
   - `.card-meta`
   - `.stat` chips and b/svg sub-elements
   - `.sessions`, `.session`, `.s-id`, `.s-adw`
   - `.actions`, `.actions button`, `.actions .danger`
   - `@keyframes pulse` and `@keyframes spin`

### Step 3: Update `workflow-card.mjs`
1. Re-implement `renderWorkflowCard(snapshot, options)`:
   - Generate `.card` with `data-workflow-id` and status class (`running`, `fail`, etc.).
   - Card Head (`.card-head`):
     - `.run-id` containing `workflowId`.
     - Status chip `.chip.<status>` with inline SVG icon (checkmark, cross, spinner, circle) and translated label (`réussi`, `échoué`, `en cours`, `en attente`).
     - Phase dots `.dots` with `●` / `◐` glyphs per step.
     - Spacer and secondary info (e.g. `r${revision}`).
   - Card Meta (`.card-meta`):
     - Render `project › title/branch · type` in cyan mono font.
   - Stat chips row:
     - Cost: `$ -`
     - Runtime: clock icon + formatted total duration from timing or `-`
     - Tokens: token icon + `-`
   - Sub-cards block (`.sessions`):
     - Iterate steps to render `.session` sub-cards with `.s-id`, `.s-adw` (step name), status chip, and step runtime stat.
   - Card Actions (`.actions`):
     - Render restore / remove / purge action buttons with `.danger` class on remove/purge.

### Step 4: Update `projection.mjs`
1. Update list container rendering in `renderWorkflowGroup` or `renderProjection` to wrap workflow cards in `<div class="runs">`.
2. Ensure click events delegation correctly distinguishes card clicks (navigate to detail) from button clicks (`data-action`) and link clicks.

### Step 5: Verification & Testing
1. Test with existing JS unit tests if present or run syntax/lint checks.
2. Verify ESM modules load without errors in browser or node test runner.
3. Validate http://127.0.0.1:8141/ runs list page and detail view for test-run-1.

---

## Verification Commands

1. **Static Analysis & Linting**:
   ```bash
   pnpm lint
   ```

2. **Project Tests**:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)"
   ```

3. **Manual Browser / Service Verification**:
   - Start or check factory service running on port 8141.
   - Open `http://127.0.0.1:8141/`
   - Verify Runs List matches Coday Dockyard card layout (chips, stats, dots, cyan meta, dark theme).
   - Click `test-run-1` -> Verify detail page shows ONLY SSSF waterfall timeline at top + phase panel when step is clicked, with NO secondary "ACTEUR · TEMPS" block.
