# Real Cost Integration Plan for `apps/cockpit-v2`

## Context & Overview

The goal of this task is to integrate real cost metrics into `apps/cockpit-v2` based on metrics returned by `factory-service`.
When a backend run includes real cost information via metrics, the frontend models, mappers, store computations, and UI components must properly reflect the cost, including indicating uncertainty when `unknownCostCount > 0`.

The backend Kotlin code must NOT be modified.

---

## 1. Core Model Extension (`apps/cockpit-v2/src/app/core/models.ts`)

### Changes Required:
1. Extend `RunSummary`:
   - Add optional property `unknownCostCount?: number`.
2. Extend `SessionDetail`:
   - Add optional property `unknownCostCount?: number`.
3. Update `CostSummary`:
   - Add optional property `unknownCostCount?: number` (sum of `unknownCostCount` of active runs).

---

## 2. Defensive Payload Parsing and Mapping (`apps/cockpit-v2/src/app/core/mappers.ts`)

### Changes Required:
1. Handle payload shape for metrics `realCost` block:
   - Metrics payload may present `metrics.realCost` as `{ cost: number, unknownCostCount: number, liveTokens: number, paused: boolean, active: boolean, runCostThreshold: number | null }` or nested under `{ realCost: { ... } }` or inside metrics directly.
   - Extract `realCost` object safely using `asObject(metricsObj?.['realCost']) ?? metricsObj`.
2. Mapping rules:
   - `costUsd`:
     - If `realCost.cost` (a finite number) is present in `metrics`, map it to `RunSummary.costUsd` and `SessionDetail.costUsd`.
     - Defensive fallback: If `metrics` / `realCost` is missing/malformed, fall back to existing `costUsd` on projection/snapshot if present, else default to `0`.
   - `unknownCostCount`:
     - If `realCost.unknownCostCount` is a finite number, map to `RunSummary.unknownCostCount` and `SessionDetail.unknownCostCount`.
     - If missing/undefined, default to `0` or `undefined` (or `0` when absent, but store explicitly when > 0). NEVER flatten unknown cost to 0 when `unknownCostCount > 0`.
   - `tokens`:
     - Map `realCost.liveTokens` to tokens counter when available and > 0 (e.g., `SessionDetail.tokens` if `liveTokens > 0`, falling back to existing token derivation logic).
3. Pure, fully testable functions.

---

## 3. Store Updates & State Aggregation (`apps/cockpit-v2/src/app/core/factory.store.ts`)

### Changes Required:
1. Session & Run enrichment:
   - In `enrichSession()`, when `getMetrics` returns metrics containing `realCost`, update `SessionDetail` via mapper.
   - Also update the `RunSummary` associated with active sandboxes with the mapped `costUsd` and `unknownCostCount`.
2. Cost summary calculation (`costs` computed signal):
   - Recalculate `CostSummary.workflowsUsd` based on the real costs of active runs (`activeSandboxes()`).
   - Calculate `CostSummary.unknownCostCount` by summing `s.run?.unknownCostCount ?? 0` across active sandboxes/runs.
3. Code Comments & Backward Compatibility:
   - Explicitly document in code comments that sandbox fleet containers, `archayUsd`, and `destroyedUsd` remain MOCK because no backend API exists yet for sandbox lifecycle.
   - Maintain full backwards compatibility with public signals/methods of `FactoryStore`.

---

## 4. Format Pipes & UI Components Handling Uncertainty (`unknownCostCount > 0`)

### Requirement:
When `unknownCostCount > 0`, format/display cost honestly (e.g., with `≥` prefix like `≥ $X.XXXX` or `$X.XXXX (N tours au coût inconnu)` or an explicit badge/tooltip signaling uncertainty). NEVER display as plain exact cost without uncertainty signal when `unknownCostCount > 0`.

### Changes Required:
1. `apps/cockpit-v2/src/app/shared/pipes/format.pipes.ts`:
   - Update `UsdPipe` transform method:
     `transform(value: number | null | undefined, digits = 4, unknownCostCount = 0): string`
     If `unknownCostCount > 0`, prepend `≥ ` to the output (e.g., `≥ $1.0723`).
2. `apps/cockpit-v2/src/app/shared/ui/metric-chip.component.ts`:
   - Optionally add support for displaying an uncertainty badge/indicator or tooltip if passed an `uncertain` / `unknownCostCount` input, or allow template content formatted with `UsdPipe`.
3. Views / Component Templates:
   - `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.html`:
     Pass `run.unknownCostCount` to `usd` pipe or display uncertainty indicator when `run.unknownCostCount > 0`.
   - `apps/cockpit-v2/src/app/features/session/session-page.component.html`:
     Pass `s.unknownCostCount` to `usd` pipe for cost display.
   - `apps/cockpit-v2/src/app/layout/shell.component.html`:
     In topbar cost summary (`c.workflowsUsd`), if `c.unknownCostCount > 0`, show `≥` or uncertainty hint.

---

## 5. Testing Plan (`apps/cockpit-v2/src/app/core/`)

### Tests to add/update:
1. `mappers.spec.ts`:
   - Test `mapProjectionToRunSummary` with `metrics.realCost` present (mapping `costUsd`, `unknownCostCount`).
   - Test `mapProjectionToSessionDetail` with `metrics.realCost` containing `cost`, `unknownCostCount > 0`, and `liveTokens`.
   - Test graceful degradation when `metrics` or `realCost` is missing/malformed (falls back to projection `costUsd`, `unknownCostCount` 0/undefined).
   - Test non-zero `unknownCostCount` is preserved and NOT flattened to 0.
2. `factory.store.spec.ts`:
   - Test workflow enrichment with `metrics` payload containing `realCost`. Verify store updates `SessionDetail` and sandbox `RunSummary`.
   - Test `costs()` calculation aggregates `workflowsUsd` from real workflow costs and calculates `unknownCostCount` propagation.
3. `format.pipes.spec.ts` (or `mappers.spec.ts` / new spec):
   - Test `UsdPipe` formats plain costs as `$X.XXXX` and uncertain costs (`unknownCostCount > 0`) with `≥ $X.XXXX`.

---

## Verification Criteria

Run the following checks to confirm everything passes:
- `pnpm nx test cockpit-v2`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`
