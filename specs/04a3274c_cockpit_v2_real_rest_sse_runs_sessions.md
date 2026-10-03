# Plan: Real REST + SSE Integration for Runs and Sessions in `apps/cockpit-v2`

## Overview
Connect `apps/cockpit-v2` to real backend data from Spring/Kotlin `factory-service` via `/api/factory/workflows` for RUNS and SESSIONS. Replace mock data for workflow projections, timelines, and sessions while keeping sandbox fleet and aggregated costs mock-based (as no Sandbox API exists yet).

The implementation strictly stays within `apps/cockpit-v2/src/app/core/` and `apps/cockpit-v2/src/app/app.config.ts`.

---

## Technical Context & Scope

### Modified/Created Files
- `apps/cockpit-v2/src/app/app.config.ts` — Add `provideHttpClient()`
- `apps/cockpit-v2/src/app/core/factory-api.service.ts` — New Angular Injectable HTTP service for `/api/factory/workflows/*`
- `apps/cockpit-v2/src/app/core/sse.service.ts` — New Angular Injectable service for SSE (`/api/factory/workflows/stream`) with auto-reconnect and invalidation/reconnect notifications
- `apps/cockpit-v2/src/app/core/mappers.ts` — New mapper functions mapping Projection v2 JSON + backend metrics/timing/evidence into UI models (`RunSummary`, `SessionDetail`, `RunEvent`, `PhaseSegment`, `TimelineLane`, `TimelineBlock`)
- `apps/cockpit-v2/src/app/core/factory.store.ts` — Update `FactoryStore` to inject `FactoryApiService` and `SseService`, fetch real workflows, handle SSE invalidations/reconnects, map runs onto active sandboxes, and maintain backwards-compatible signals/methods with graceful degradation.
- `apps/cockpit-v2/src/app/core/models.ts` — (Only if needed) add optional helper fields if necessary, preserving existing types.

### Test Files to Create
- `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`
- `apps/cockpit-v2/src/app/core/sse.service.spec.ts`
- `apps/cockpit-v2/src/app/core/mappers.spec.ts`
- `apps/cockpit-v2/src/app/core/factory.store.spec.ts` (updated)

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Update `app.config.ts`
Add `provideHttpClient()` to `providers` array in `apps/cockpit-v2/src/app/app.config.ts`.

```typescript
import { provideHttpClient } from '@angular/common/http'
// ...
export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(),
    // ...
  ]
}
```

### Step 2: Implement `FactoryApiService` (`core/factory-api.service.ts`)
Create `@Injectable({ providedIn: 'root' })` wrapping `HttpClient`.

#### Endpoints
- `getWorkflows(state: 'active' | 'removed' = 'active', namespaceId?: string): Observable<any[]>` -> `GET /api/factory/workflows?state=...`
- `getWorkflow(id: string, namespaceId?: string): Observable<any>` -> `GET /api/factory/workflows/:id`
- `getTiming(id: string, namespaceId?: string): Observable<any>` -> `GET /api/factory/workflows/:id/timing`
- `getEvidence(id: string, namespaceId?: string): Observable<any>` -> `GET /api/factory/workflows/:id/evidence`
- `getMetrics(id: string, namespaceId?: string): Observable<any>` -> `GET /api/factory/workflows/:id/metrics`

#### Header & Envelope & Error Requirements
- Headers: Include `X-Correlation-Id` on all requests. Generate a default correlation string (e.g. `cockpit-v2-${Date.now()}-${Math.random().toString(36).substr(2, 6)}`) if not provided. Include `X-Namespace-Id` (or query param `namespaceId`) ONLY when `namespaceId` is provided and non-empty. Omit query param `namespaceId` when absent/empty.
- Envelope unwrapping: If response is `{ data: T }`, return `T`. Otherwise if it's raw JSON object/array, return it.
- Structured Error Handling: Catch HTTP errors and normalize into structured error objects `{ code: string, message: string, status: number, raw: any }`.

### Step 3: Implement `SseService` (`core/sse.service.ts`)
Create `@Injectable({ providedIn: 'root' })` wrapping browser `EventSource`.

#### Functionality
- URL: `/api/factory/workflows/stream` (append `?namespaceId=...` only when `namespaceId` is defined and non-empty).
- Named SSE events handling: `workflow-projection-updated`, `workflow-projection-removed`, `workflow-projection-restored`, `workflow-projection-purged`.
- Expose observables / signals:
  - `invalidations$`: Subject/Observable emitting payload/event whenever an SSE update event arrives.
  - `reconnected$`: Subject/Observable emitting boolean/void whenever connection is restored after connection loss.
- Resilient Auto-Reconnect:
  - Track initial connection status. If connection breaks (`onerror`), schedule reconnect with backoff/timer.
  - On successful reconnection after a drop, emit on `reconnected$` so subscribers know to refetch REST state.
  - Clean `close()` method when service/subscription is destroyed.

### Step 4: Implement `Mappers` (`core/mappers.ts`)
Implement pure functions mapping Projection v2 JSON + timing + evidence + metrics to UI models (`RunSummary`, `SessionDetail`, `RunEvent`, `PhaseSegment`, `TimelineLane`).

#### Model Mappers
1. `mapWorkflowStateToRunStatus(state?: string, steps?: any[]): RunStatus`
   - Map backend statuses (`running`, `completed`, `failed`, `cancelled`, `queued`, `active`) to `'running' | 'succeeded' | 'failed' | 'queued'`.
2. `mapStepsToPhaseSegments(steps: any[], totalDurationSec: number): PhaseSegment[]`
   - Calculate step durations. Map step statuses (`completed`, `running`, `pending`, `failed`). Assign tones based on step responsibility (`code` -> cyan, `human` -> amber, `agent` -> violet/purple). Calculate `ratio = duration / totalDuration`.
3. `mapProjectionToRunSummary(item: any): RunSummary`
   - Maps backend `workflowId`, `projection.title`/`workflow`, status, cost, duration, tokens, phases.
4. `mapProjectionToLanes(projection: any, timing?: any): TimelineLane[]`
   - Implement temporal lanes logic matching vanilla `temporal-lanes/projection` logic:
     - Lane classification by `step.lane` or `step.responsibility.kind` (`agent` | `code` | `human`).
     - Group steps into lanes (`engineer` for human, `code` for code, `agent:<name>` for agent).
     - Calculate block start/end timestamps relative to workflow start (`startSec`, `endSec`).
     - Map status and error ticks.
5. `mapProjectionToSessionDetail(workflow: any, timing?: any, evidence?: any, metrics?: any): SessionDetail`
   - Build complete `SessionDetail` including steps, lanes, events, phase details, token counts (`tokensRead`, `tokensWritten`), and cost.

### Step 5: Update `FactoryStore` (`core/factory.store.ts`)
Inject `FactoryApiService` and `SseService`.

#### Behavior
1. Initialization:
   - On init, trigger REST fetch of workflows via `FactoryApiService.getWorkflows('active')`.
   - On success, map real workflow projections into `RunSummary` items.
   - Attach mapped real runs onto active mocked sandboxes (e.g. replacing mock `run` on active sandboxes with fetched workflow runs or building sandbox mappings).
   - Maintain mock sandboxes fleet and mock archay/destroyed costs.
2. SSE Synchronization:
   - Subscribe to `SseService.invalidations$` and `reconnected$`.
   - On invalidation or reconnection, re-fetch active workflows and update signals.
3. Real Session Detail Lookup:
   - In `session(runId: string)`: if `runId` matches a loaded real workflow, fetch/derive its detailed state, evidence, and metrics; fallback cleanly to mock session `SESSION_872641A8` if not found or on error.
4. Graceful Degradation:
   - If REST API call fails (e.g., backend unavailable or 500 error), catch error gracefully, keep sandboxes intact with empty/fallback run list, log diagnostic warning, and avoid crashing Angular app.
5. Store Public Surface Preserved:
   - `sandboxes`: `Signal<Sandbox[]>`
   - `recentTasks`: `Signal<RecentTask[]>`
   - `showDestroyed`: `WritableSignal<boolean>`
   - `activeSandboxes`: `Signal<Sandbox[]>`
   - `destroyedSandboxes`: `Signal<Sandbox[]>`
   - `visibleSandboxes`: `Signal<Sandbox[]>`
   - `costs`: `Signal<CostSummary>`
   - `session(runId: string)`: method returning `SessionDetail | undefined`
   - `destroy(name: string)`: method
6. Code Documentation:
   - Explicitly document in `factory.store.ts` comments what data is REAL (workflow runs, session projections, SSE updates) vs MOCKED (sandbox container fleet, archay costs, destroyed cost aggregate).

### Step 6: Tests Verification
Create unit tests with Angular TestBed / `provideHttpClientTesting()`:
1. `factory-api.service.spec.ts`: Test GET endpoints, envelope unwrapping (`{ data }`), header propagation (`X-Correlation-Id`), optional `namespaceId` handling, and error normalization.
2. `sse.service.spec.ts`: Test EventSource setup, handling of SSE event types, invalidation output, auto-reconnect logic, and reconnection notification.
3. `mappers.spec.ts`: Test step/lane mapping, responsibility classification (`agent`/`code`/`human`), relative timing math, phase segments, and `SessionDetail` creation.
4. `factory.store.spec.ts`: Test loading workflows, updating sandboxes with real runs, SSE event triggers, graceful degradation when API fails, and preserved public API surface.

Run verification command:
`pnpm nx test cockpit-v2`

---

## Verification Plan

1. Executing `pnpm nx test cockpit-v2` runs all core service, mapper, and store unit tests.
2. Ensure no lint errors with `pnpm nx lint cockpit-v2` (or root `pnpm lint`).
3. Build check with `pnpm nx build cockpit-v2`.
