# Plan Implementation: Dé-mocker l'écran Sandboxes du cockpit v2 (apps/cockpit-v2)

## Overview & Goal
Remplacer la flotte mock de conteneurs dans `apps/cockpit-v2` par les workflows actifs réels chargés depuis `FactoryApiService.getWorkflows('active')`, conformément à la décision architecturale (pas d'API de flotte de conteneurs côté factory-service, la vérité disponible ce sont les workflows actifs réels).

---

## Targeted Files & Required Changes

### 1. `apps/cockpit-v2/src/app/core/models.ts`
- **Sandbox**:
  - Make optional: `roster?`, `archayCostUsd?`.
  - Update `status`: allow `SandboxStatus` ('working' | 'idle' | 'destroyed') or optional status derived from workflow state.
  - Add optional domain fields if useful for real workflows: `namespace?`, `workflowType?`, `ticket?`, `branch?`.
  - Maintain `name`, `project`, `branch?`, `finalCostUsd?`, `run?`.
- **CostSummary**:
  - `active`: number of active workflows.
  - `workflowsUsd`: sum of real workflow costs (`costUsd`).
  - `totalUsd`: equal to `workflowsUsd`.
  - Make optional or keep present for backwards compatibility: `archayUsd?: number`, `destroyedUsd?: number`.
  - Conserve `unknownCostCount?: number`.

### 2. `apps/cockpit-v2/src/app/core/factory.store.ts`
- **Data Source & Signals**:
  - Remove initial reliance on `SANDBOXES` mock data. Initialize `sandboxes` signal with `[]` (empty array).
  - Remove `attachRunsToSandboxes` and `SANDBOXES` / `RECENT_TASKS` imports from `mock-data.ts`.
  - Remove/neutralize `destroy(name)`: method converted to a no-op or removed if no backend operation exists.
  - Keep `showDestroyed` / `recentTasks` signal safely present or empty without hardcoded mock data.
- **Workflow-to-Sandbox Mapping**:
  - Directly derive the list of sandboxes from the active workflow snapshots (`items` returned by `getWorkflows('active')`).
  - For each snapshot in `items`, map to a `Sandbox` object:
    - `name`: `workflowId` or snapshot title / ticket / goal. (e.g., `snapshot.workflowId` or `snapshot.projection?.title` / goal).
    - `project`: `namespaceId` or `relations?.ticket` or `'coday'` by default.
    - `branch`: `relations?.ticket` or branch if present in snapshot, else optional.
    - `status`: `'working'` if workflow status is running/active/waiting_human; `'idle'` if idle/ready/pending.
    - `run`: `RunSummary` mapped via `mapProjectionToRunSummary(snapshot)`.
- **Cost Calculation (`costs` computed signal)**:
  - `active = sandboxes.length` (or active workflows count).
  - `workflowsUsd = sum(s.run?.costUsd ?? 0)`.
  - `totalUsd = workflowsUsd`.
  - `unknownCostCount = sum(s.run?.unknownCostCount ?? 0)`.
  - Do NOT hardcode fake numbers (`archayUsd` and `destroyedUsd` set to 0 or omitted).
- **Graceful Degradation**:
  - If backend is offline or returns error in `load()`, set `sandboxes.set([])`, `sessions.set(new Map())`, `enrichment.clear()`. No crash, no fallback onto `SANDBOXES` mock.
- **PRESERVE ALL GOVERNED ACTIONS & SESSIONS API**:
  - Do NOT touch `session()`, `getMetrics`, `getInteractions`, `getAttempts`, `getActions`, `replyInteraction`, `retry`, `cancelAttempt`, `continueCost`, `stopCost`, `allowedActions`, `blockers`.

### 3. UI Components (`apps/cockpit-v2/src/app/features/sandboxes/`)
- **`sandboxes-page.component.html` / `sandboxes-page.component.ts`**:
  - Update cost KPI cards: display active workflows count and real costs (`workflowsUsd` / `totalUsd`). Hide or remove "Sandboxes détruites" / "Archay" KPI cards with invented figures.
  - Update empty state: show a neutral message when `sandboxes().length === 0` (e.g. "Aucun workflow actif.").
  - Form ("mount / best-of-N"): keep as neutral informational controls without pretending to execute remote actions if no backend exists.
- **`sandbox-card.component.html` / `sandbox-card.component.ts`**:
  - Hide or adapt fields without real sources (`roster`, `wave`, `archayCostUsd`).
  - Hide or remove the "Détruire" button if no backend action is attached to it.
- **`apps/cockpit-v2/src/app/layout/shell.component.html`**:
  - Update topbar cost pill to display active workflows and real costs without relying on mock `archayUsd` / `destroyedUsd` values.

### 4. `apps/cockpit-v2/src/app/core/mock-data.ts`
- Remove `SANDBOXES` and `RECENT_TASKS`.
- Retain `SESSION_872641A8` as required for fallback / session testing.

### 5. Tests (`apps/cockpit-v2/src/app/core/factory.store.spec.ts` & component specs)
- Update `factory.store.spec.ts`:
  - Test initial state with empty sandboxes when API has no workflows.
  - Test direct derivation of Sandboxes from active workflows REST payload.
  - Test correct calculation of `CostSummary` (`workflowsUsd`, `totalUsd`, `unknownCostCount`).
  - Test graceful degradation (empty `sandboxes` list when REST call fails with 503/error).
  - Test SSE invalidation refetching real workflows.
  - Verify all existing tests for governed actions, session details, attempts, and interactions pass without failure.

---

## Verification Plan

Run the following commands to ensure complete quality compliance:
1. `pnpm nx test cockpit-v2`
2. `pnpm nx lint cockpit-v2`
3. `pnpm nx build cockpit-v2`
