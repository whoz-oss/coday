# Spec / Plan: Phase 11 — Workstream Cockpit Real HTTP Wiring

## Summary

This plan details the Phase 11 implementation for re-wiring the Angular Workstream Cockpit (`apps/client`) to real Factory HTTP endpoints (`/api/factory/**`), introducing `FactoryWorkstreamService` with automatic Factory envelope unwrapping (`{ data: ... }`), ETag / revision freshness tracking, dev mock toggling (`useMock`), allowed actions derivation, and state badge distinction for waiting/blocked/indeterminate and completed/archived/runtime-closed states.

**Strict Scope**: Front Angular in `apps/client/` ONLY. No Kotlin backend (`factory-service/`), no `agentos/` plugin, no Flyway/Neo4j migrations, no release pipeline changes.

---

## 1. Overview & Architecture

### 1.1 Goals
1. Create `FactoryWorkstreamService` in `apps/client/src/app/core/services/factory-workstream.service.ts` using Angular `HttpClient`.
2. Support envelope unwrapping for Factory responses `{ data: T }` and HTTP error formatting for `{ error: { code, message, details } }`.
3. Track ETag / revision / sync freshness (`revision`, `asOf` timestamp) from HTTP headers/payloads.
4. Support Dev Mock Toggle (`useMock: false` by default, or configurable via property / flag) falling back seamlessly to `WorkstreamMockService`.
5. Map real DTOs and align models in `apps/client/src/app/core/models/workstream.model.ts` (e.g. `allowedActions`, `WorkstreamProjectionResponse`, `AllowedActionDto`, `BlockerCode`, `AttemptStatus`).
6. Update `WorkstreamCockpitComponent` and child views to inject `FactoryWorkstreamService`, handle loading/error/empty states, and drive actions dynamically from `allowedActions`.
7. Achieve 100% pass rate for Jest unit tests (`pnpm nx test client`), Angular lint (`pnpm nx lint client`), and Angular build (`pnpm nx build client`).

---

## 2. Real Endpoint & Envelope Mapping Matrix

| Angular Service Method | Target Factory Endpoint | Envelope & Unwrapping | Revision / ETag Source |
|---|---|---|---|
| `getWorkstream(workstreamId)` | `GET /api/factory/workstreams/{workstreamId}` | Returns JSON object | `revision` field in payload |
| `getWorkstreamProjection(workstreamId)` | `GET /api/factory/workstreams/{workstreamId}/projection` | Returns `WorkstreamProjectionResponse` | `ETag` header (`"12"`) / `workstreamRevision` field |
| `listWorkflows(workstreamId)` | `GET /api/factory/workflows?workstreamId=...` | Returns `{ data: WorkflowSummaryDto[] }` or array | `revision` on items |
| `getWorkflow(workflowId)` | `GET /api/factory/workflows/{workflowId}` | Returns `{ data: WorkflowDetailDto }` | `revision` field in detail |
| `getStepAttempts(workflowId, stepId)` | `GET /api/factory/workflows/{workflowId}/attempts?stepId=...` (or filtered) | Returns `{ data: StepAttemptDto[] }` or `StepAttemptDto[]` | `revision` field on attempts |
| `getBlockers(workflowId)` | `GET /api/factory/workflows/{workflowId}/actions` or `getWorkflow` | Returns `{ data: { blockers: [...] } }` | Derived from workflow revision |
| `getAllowedActions(workflowId)` | `GET /api/factory/workflows/{workflowId}/actions` | Returns `{ data: { allowedActions: AllowedActionDto[], blockers: ... } }` | `expectedRevision` in response |
| `getRequiredHumanActions(workflowId)` | `GET /api/factory/workflows/{workflowId}/interactions` | Returns `{ data: InteractionDto[] }` | `expectedRevision` in interaction |
| `getPlanChangeProposals(workflowId)` | `GET /api/factory/plan-change-proposals?workflowId=...` | Returns `{ data: PlanChangeProposalDto[] }` | `revision` on proposal |
| `getControllerHistory(workflowId)` | `GET /api/factory/workstreams/{workstreamId}/controller-case/history` | Returns `{ data: ControllerHistoryDto }` (404 handled gracefully as empty) | `revision` on history |
| `requestAgentRetry(...)` | `POST /api/factory/workflows/{workflowId}/retries` | Unwraps `{ data: RetryAckDto }` | Updated `revision` |
| `respondToInteraction(...)` | `POST /api/factory/workflows/{workflowId}/interactions/{id}/reply` | Unwraps `{ data: ReplyAckDto }` | Updated `revision` |
| `decidePlanChange(...)` | `POST /api/factory/plan-change-proposals/{id}/decide` | Unwraps `{ data: ProposalAckDto }` | Updated `revision` |

---

## 3. Detailed File Changes

### 3.1 `apps/client/src/app/core/models/workstream.model.ts`
- **Add Envelopes & Types**:
  ```ts
  export interface FactoryDataEnvelope<T> {
    data: T
  }

  export interface FactoryErrorPayload {
    code: string
    message: string
    details?: Record<string, unknown>
  }

  export interface FactoryErrorEnvelope {
    error: FactoryErrorPayload
  }

  export interface AllowedActionDto {
    id: string
    label: string
    kind?: string
    enabled?: boolean
    reason?: string
  }

  export interface WorkflowActionsResponseDto {
    workflowId: string
    revision: number
    allowedActions: AllowedActionDto[]
    blockers: WorkflowBlockerDto[]
  }

  export interface WorkstreamProjectionResponseDto {
    workstreamId: string
    workstreamRevision: number
    workflows: WorkflowSummaryDto[]
    asOf: string
  }
  ```
- Ensure status/blocker enums support all Phase 10 & Phase 0 values:
  - `WorkflowState`: `'absent' | 'existing' | 'removed' | 'purged' | 'completed' | 'archived' | 'runtime-closed'`
  - `BlockerCode`: `'WAITING_HUMAN_INTERACTION' | 'STEP_BLOCKED' | 'ATTEMPT_FAILED' | 'REAL_COST_PAUSED' | 'VERIFICATION_FAILED' | 'UNKNOWN_RUNTIME'`
  - `AttemptStatus`: `'pending' | 'claiming' | 'starting' | 'running' | 'waiting_human' | 'succeeded' | 'failed' | 'indeterminate' | 'interrupted'`

### 3.2 `apps/client/src/app/core/services/factory-workstream.service.ts` (NEW)
- `@Injectable({ providedIn: 'root' })`
- **Dependencies**: `HttpClient`, `WorkstreamMockService`.
- **Config / State**:
  - `useMock = signal<boolean>(false)` (or property `setUseMock(boolean)` / configurable via dev flag).
  - `lastSyncAsOf = signal<string | null>(null)`.
  - `lastRevision = signal<number>(0)`.
- **Envelope Unwrapping Helper**:
  ```ts
  private unwrap<T>(obs: Observable<T | FactoryDataEnvelope<T>>, observeHttpResponse = false): Observable<T> { ... }
  ```
- **HTTP Methods with Fallback to Mock**:
  Every public method checks `this.useMock()`:
  - If `useMock()` is `true`: calls corresponding method in `WorkstreamMockService`.
  - If `useMock()` is `false`: executes `this.http.get/post(...)`, pipe through `map(res => unwrap(res))` and `tap` to update `lastSyncAsOf` (from header or payload timestamp) and `lastRevision`. On HTTP error, catches and formats structured `FactoryErrorPayload` or falls back gracefully (e.g. 404 on optional controller history -> returns empty history DTO).
- **Commands**:
  - `requestAgentRetry(workflowId, stepId, expectedRevision, reason)` -> `POST /api/factory/workflows/{workflowId}/retries`
  - `respondToInteraction(workflowId, interactionId, actionId)` -> `POST /api/factory/workflows/{workflowId}/interactions/{interactionId}/reply`
  - `decidePlanChange(proposalId, decision)` -> `POST /api/factory/plan-change-proposals/{proposalId}/decide`

### 3.3 `apps/client/src/app/components/workstream-cockpit/workstream-badges.ts`
- Verify / enhance badge helper functions for visual distinctions:
  - `waiting_human` / `WAITING_HUMAN_INTERACTION` -> `ws-badge ws-badge--amber`
  - `blocked` / `STEP_BLOCKED` / `ATTEMPT_FAILED` -> `ws-badge ws-badge--red`
  - `indeterminate` / `UNKNOWN_RUNTIME` -> `ws-badge ws-badge--purple-grey`
  - `completed` -> `ws-badge ws-badge--green`
  - `archived` / `runtime-closed` / `removed` -> `ws-badge ws-badge--muted`
  - `running` / `in-flight` -> `ws-badge ws-badge--blue-grey`

### 3.4 `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.ts`
- Change injection: inject `FactoryWorkstreamService` instead of `WorkstreamMockService` directly.
- Add Signals for UI State:
  - `isLoading = signal<boolean>(false)`
  - `errorMessage = signal<string | null>(null)`
  - `allowedActions = signal<AllowedActionDto[]>([])`
- Update Data Flow:
  - In `loadWorkstream()` and `onSelectWorkflow()`:
    - Set `isLoading.set(true)`, reset `errorMessage.set(null)`.
    - Fetch workflow detail, allowed actions, step lanes, blockers, interactions, plan proposals, controller history.
    - Set `isLoading.set(false)`.
    - Handle errors by setting `errorMessage.set(...)`.
  - Driven by `allowedActions`: display actions dynamically in components, avoiding any hardcoded browser worker triggering.
  - Expose a toggle or badge for `useMock` indicator in header.

### 3.5 `apps/client/src/app/components/workstream-cockpit/human-interactions/` & `step-attempts/`
- Ensure retry/interaction buttons check `allowedActions` or DTO-provided `actions` strictly.

---

## 4. Strategy for Unit Tests & Quality Verification

### 4.1 `apps/client/src/app/core/services/factory-workstream.service.spec.ts` (NEW)
- Use `provideHttpClient()` and `HttpTestingController` (or `provideHttpClientTesting()`).
- Tests:
  1. `getWorkstream`: issues GET request to `/api/factory/workstreams/:id`, unwraps `{ data: ... }`, updates `lastRevision`.
  2. `listWorkflows`: issues GET request, unwraps items array.
  3. Envelope Unwrapping: test both `{ data: ... }` response and unwrapped direct object response.
  4. Dev Mock Switch: set `useMock(true)`, verify it delegates to `WorkstreamMockService` without HTTP requests.
  5. Command endpoints (`requestAgentRetry`, `respondToInteraction`, `decidePlanChange`): verify POST body structure and response unwrapping.
  6. HTTP Error Handling: mock 400/500 error responses with Factory error envelope `{ error: { code, message } }` and ensure service propagates formatted errors.

### 4.2 `apps/client/src/app/components/workstream-cockpit/workstream-cockpit.component.spec.ts`
- Update test bed setup:
  - Provide `provideHttpClientTesting()` or mock `FactoryWorkstreamService`.
  - Test workflow loading, step selection, error banner rendering when API fails, and freshness revision display.

### 4.3 Target Verification Commands
- `pnpm nx test client` (must pass all tests)
- `pnpm nx lint client` (must pass with zero errors)
- `pnpm nx build client` (must compile without errors)

---

## 5. Verification Checklist & Constraints

- [ ] Front Angular code ONLY in `apps/client/`. No modifications to `factory-service`, `agentos/`, Flyway/Neo4j migrations, or release pipeline.
- [ ] No browser-launched workers.
- [ ] Envelope unwrapping handles both wrapped `{ data: T }` and direct payloads cleanly.
- [ ] Revision & `asOf` freshness exposed in cockpit header.
- [ ] Status badges accurately reflect Phase 10 and Phase 0 states (`waiting_human`, `blocked`, `indeterminate`, `completed`, `archived`, `runtime-closed`).
- [ ] `allowedActions` dynamically governs user actions.
- [ ] All Nx tasks (`test`, `lint`, `build`) for `client` pass.
