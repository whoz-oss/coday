# Plan: History Page UI Updates and Unit Tests

## Context
The Cockpit v2 History Page (`apps/cockpit-v2/src/app/features/history/history-page.component.ts`, `.html`) shows all sandboxes/runs (active and completed/stopped/destroyed).
The backend store (`FactoryStore`) already loads active sandboxes and destroyed/removed workflows into `store.sandboxes()`.

We need to update the UI copy, chip options, and status rendering on the History screen so completed/stopped/destroyed runs are clearly labeled according to specifications, and create a comprehensive unit test suite in `apps/cockpit-v2/src/app/features/history/history-page.component.spec.ts`.

## Constraints
- **DO NOT EDIT**: `apps/cockpit-v2/src/app/core/*`, `apps/cockpit-v2/src/app/features/sandboxes/*`, or `apps/cockpit-v2/src/app/layout/*`.
- File edits must strictly stay within `apps/cockpit-v2/src/app/features/history/`.
- Must pass `nx test cockpit-v2` and `nx lint cockpit-v2`.

---

## Detailed Modifications

### 1. Template Updates (`apps/cockpit-v2/src/app/features/history/history-page.component.html`)

- **Header description**: Update `<p class="sf-muted">` to exact requirement:
  `"Runs terminés, arrêtés et détruits, avec leur coût et phases"`
- **Filter Chip options**: Update `mat-chip-listbox` options:
  - Keep `value="all"`: `"Tous · {{ counts().all }}"`
  - Keep `value="working"`: `"En cours · {{ counts().working }}"`
  - Keep `value="destroyed"`: `"Terminés / Arrêtés · {{ counts().destroyed }}"`
- **Status column status chips**: Update the `sf-status-chip` in the `status` table column:
  - For `r.status === 'destroyed'`, render:
    `<sf-status-chip tone="neutral" size="sm" dot>terminé / arrêté</sf-status-chip>`
  - For `r.status !== 'destroyed'`, render:
    `<sf-status-chip tone="blue" size="sm" icon="progress_activity" spin>en cours</sf-status-chip>`

### 2. Component Logic (`apps/cockpit-v2/src/app/features/history/history-page.component.ts`)

- Verify `rows` derives properly from `store.sandboxes()`:
  `store.sandboxes().map((s) => ({ ...s, cost: s.run?.costUsd ?? s.finalCostUsd ?? 0 }))`
- Verify filter logic:
  - `st === 'all'` includes all items for the selected project.
  - `st === 'working'` includes items where `status !== 'destroyed'`.
  - `st === 'destroyed'` includes items where `status === 'destroyed'`.
- Verify total cost, cost bars, pagination, sort, and CSV export operate cleanly on `filtered()` / `rows()`.

### 3. Unit Tests (`apps/cockpit-v2/src/app/features/history/history-page.component.spec.ts`)

Create a new unit test suite using Angular `TestBed` and Jest, matching the pattern used in `sandboxes-page.component.spec.ts`:
- **Mock / Stub `FactoryStore`**: Provide `sandboxes: WritableSignal<Sandbox[]>` and `project` state if applicable.
- **Test Scenarios**:
  1. **Component creation & default rendering**: Header text, column headers, initial rows rendering.
  2. **Filtering by status**:
     - Status `'all'`: shows active and destroyed runs.
     - Status `'working'`: shows only active ('en cours') runs.
     - Status `'destroyed'`: shows only completed/stopped/destroyed ('terminé / arrêté') runs.
  3. **Filtering by search query**: Filter by name, branch, run ID, or workflow type.
  4. **Cost calculation**: Verify `totalCost()` and `costBars()` derive correct values and percentages.
  5. **Pagination & Sort wiring**: Verify `dataSource.sort` and `dataSource.paginator` are hooked up via effects.
  6. **CSV Export trigger**: Test calling `exportCsv()` (mocking `URL.createObjectURL`, `URL.revokeObjectURL`, `document.createElement`).

---

## Verification Steps

Run:
1. `pnpm nx test cockpit-v2` - Ensure all tests pass including the new `history-page.component.spec.ts`.
2. `pnpm nx lint cockpit-v2` - Ensure zero lint errors.
