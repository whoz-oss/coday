# Archay Architectural Mandate: Cockpit Workstream -> Runs UI Refactoring Plan

## Overview

This plan restructures the Cockpit Workstream and Runs UI in `apps/factory-cockpit/src/app/` according to the Archay Architectural Mandate (Lot G Factory Forge).

The scope is strictly isolated to the Angular frontend application (`apps/factory-cockpit/src/app/`). No Kotlin / AgentOS backend endpoints or backend code will be modified.

---

## 1. Domain Models & Identifiers (`core/models.ts`, `core/mappers.ts`)

### `core/models.ts`
- **Rename/Refactor Sandbox concept to `FactoryRun`**:
  - Define/adapt `FactoryRun` interface:
    ```typescript
    export interface FactoryRun {
      id: string // Workflow ID / run ID
      workflowType?: string
      status: SandboxStatus // 'working' | 'idle' | 'destroyed'
      namespaceId?: string // Namespace attribution, strictly required in projections/mappings
      title: string // Formerly 'name' / title of workflow
      project: string
      ticket?: string
      branch?: string
      createdAt?: string
      costUsd: number
      durationSec: number
      tokens: number
      phases: PhaseSegment[]
      controllerCaseId?: string
      finalCostUsd?: number
      run?: RunSummary
      // Deprecated optional properties maintained if needed for backwards compat
      roster?: string
      wave?: string
      archayCostUsd?: number
    }
    ```
  - Alias `Sandbox` to `FactoryRun` or migrate `Sandbox` usages to `FactoryRun`.
  - Define `WorkstreamView` interface:
    ```typescript
    export interface WorkstreamView {
      namespaceId: string
      title: string
      runs: FactoryRun[]
    }
    ```

### `core/mappers.ts`
- Update mapping logic:
  - Add `mapProjectionToFactoryRun(snapshot: unknown, forcedStatus?: SandboxStatus): FactoryRun` (or adapt existing `toSandbox` in store / mappers).
  - Ensure `namespaceId` extraction is strictly reliable using `namespaceOf(snapshot)`. Ensure `namespaceId` property is explicitly set on `FactoryRun`.

---

## 2. State & API Integration (`core/factory.store.ts`)

### Namespace Fetching & Signal Map
- Add signal `namespaces = signal<Map<string, string>>(new Map())` or `namespaceOptions = signal<NamespaceOption[]>([])`.
- In `load()`, fetch namespace list concurrently or as part of initialization using `FactoryApiService.getNamespaces()`.
  - On success: Populate the namespaces map / signal mapping `id` -> `name`.
  - Graceful fallback: On error / failure of `getNamespaces()`, log/degrade gracefully and fallback to namespace IDs as names.

### Runs & Workstreams Signals
- Update/rename `sandboxes` signal to `runs` (or `factoryRuns` signal of type `FactoryRun[]`). Keep `sandboxes` alias signal if needed for temporary component migration.
- Compute `workstreams` signal using Angular `computed()`:
  - Group active runs strictly by `namespaceId` (never by human display title/name, ensuring homonyms with identical names remain in distinct `WorkstreamView` instances).
  - Runs with missing or undefined `namespaceId` MUST be grouped into an explicit fallback workstream:
    ```typescript
    {
      namespaceId: 'unassigned',
      title: 'Sans namespace', // or 'Unassigned'
      runs: [...]
    }
    ```
  - Map each group's `namespaceId` to its human title using the `namespaces` map. If `namespaceId` is not in the map, fallback to `namespaceId` as the title.

### Cost & KPI Aggregates
- Update `costs` signal computed aggregate to iterate over `runs()`. Ensure total cost sums per run cost (`s.run?.costUsd ?? s.costUsd`), and propagates `unknownCostCount` accurately.

---

## 3. Component Hierarchy & Workstream Cards (`features/sandboxes` -> `features/workstreams`)

### Component Directory & Naming
- Refactor `features/sandboxes/` directory or rename/adapt files:
  - Rename `SandboxCardComponent` -> `RunCardComponent` (in `features/workstreams/run-card/run-card.component.ts` or `features/sandboxes/sandbox-card/sandbox-card.component.ts`).
  - Create `WorkstreamCardComponent` (`features/workstreams/workstream-card/workstream-card.component.ts`).
  - Update `SandboxesPageComponent` -> `WorkstreamsPageComponent`.

### `WorkstreamCardComponent` Specification
- Inputs: `workstream: WorkstreamView`.
- Displays workstream header:
  - Workstream human title (namespace name).
  - `namespaceId` sub-badge/label.
  - Count of runs in this workstream (`workstream.runs.length`).
  - Total workstream cost (sum of `costUsd` across `workstream.runs`, including `unknownCostCount` badge if > 0).
- **CRITICAL REQUIREMENT**: Workstream header must **NOT** carry any overall state or working badge (no status chip like "working" or "idle" on the workstream header). Independent statuses belong exclusively to individual runs.
- Keyboard navigation & accessibility: add appropriate `aria-label`, `role="region"`, and structured header tags (`<h3>` / `<h2>`).
- Body: Render each run using `RunCardComponent` in a list (`*ngFor` / `@for`).

### `RunCardComponent` Specification
- Renders individual run card details (title, workflowType, ticket, branch, status badge, duration, cost, phase bar).
- Actions: **ALL** actions remain strictly AT THE RUN LEVEL:
  - Stop (`action.emit('stop')`)
  - Remove (`action.emit('remove')`)
  - Restore (`action.emit('restore')`)
  - Ask supervisor (`openSupervisorCase`)
  - Conversation link (`buildAgentOsCaseUrl` / `controllerCaseId`)
- Actions MUST be targeted using exact run identity keys (`run.id` / `workflowId`), never display titles.

---

## 4. Routing & Navigation (`app.routes.ts`, `layout/shell.component.*`, `core/shell-state.ts`)

- In `app.routes.ts`:
  - Register route `/workstreams` pointing to `WorkstreamsPageComponent`.
  - Redirect route `/sandboxes` -> `/workstreams` (`redirectTo: 'workstreams', pathMatch: 'full'`).
- In `layout/shell.component.html` / `layout/shell.component.ts`:
  - Update primary navigation item from "Sandboxes" to "Workstreams", pointing to `/workstreams`.
- In `core/shell-state.ts`:
  - Update breadcrumb title to "Workstreams" and route to `/workstreams`.

---

## 5. History & CSV Export (`features/history/`)

### `HistoryPageComponent`
- Refactor `HistoryPageComponent` to be run-oriented ("Run history" / "Historique des runs").
- Display runs with individual run costs, status, duration, and phases.
- Update CSV export button/filename to `runs.csv`.
- Update CSV headers to strictly match run properties:
  `run_id;namespace_id;project;branch;status;workflow;cost_usd`

---

## 6. Acceptance Criteria & Unit Specs (`*.spec.ts`)

Update/add unit tests in `factory.store.spec.ts`, `workstreams-page.component.spec.ts` (or `sandboxes-page.component.spec.ts`), `run-card.component.spec.ts`, `workstream-card.component.spec.ts`, and `history-page.component.spec.ts`.

Ensure tests explicitly cover:
1. **Workstream grouping by `namespaceId`**: verify that two namespaces with identical human titles (homonyms) produce two separate workstream card groups.
2. **Independent statuses per run**: verify workstream header has no "working" or state badge; each run card displays its own status badge independently.
3. **Action targeting by ID**: verify actions (`stop`, `remove`, `ask supervisor`) invoke store methods with exact run ID / workflow ID.
4. **Unassigned / missing namespace runs**: verify runs without `namespaceId` are placed in an explicit fallback group (`namespaceId: 'unassigned'`).
5. **Graceful fallback for `getNamespaces()`**: verify application loads and functions cleanly when `getNamespaces()` fails or returns an error.
6. **Keyboard navigation & accessibility**: verify `aria-label`s on workstreams and runs.
7. **Passing test suite**: All unit tests pass with `pnpm nx affected -t test`.

---

## Verification Plan

Run the test suite using Nx:
```bash
pnpm nx test factory-cockpit
```
Ensure 100% test passing before completing the task.
