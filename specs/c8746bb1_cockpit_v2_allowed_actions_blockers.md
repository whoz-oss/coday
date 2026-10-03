# Plan: Cockpit v2 Actionable & Backend-Driven (AllowedActions / Blockers)

## Overview & Architecture Invariant
The cockpit v2 (`apps/cockpit-v2`) must become fully actionable and strictly driven by the backend authority.
**Core Invariant**: The cockpit UI only displays and triggers actions that the backend explicitly authorizes in `allowedActions`. It never decides actions on its own, nor does it fabricate identifiers, step IDs, or revisions.

All actions rely on fetching `GET /api/factory/workflows/{id}/actions?namespaceId=...` and POSTing to the respective action endpoints on `/api/factory/workflows/{id}/...`.

---

## Workspace & Target Files

1. **Service**: `apps/cockpit-v2/src/app/core/factory-api.service.ts`
   - Add `GetActionsResponse`, action payload types.
   - Implement `getActions(workflowId, namespaceId?)`.
   - Implement POST methods: `replyInteraction`, `openRetry`, `cancelAttempt`, `continueCost`, `stopCost`.
2. **Models**: `apps/cockpit-v2/src/app/core/models.ts`
   - Add types/interfaces for `AllowedAction`, `WorkflowBlocker`, and relevant payloads.
   - Extend `SessionDetail` with optional `allowedActions?: AllowedAction[]` and `blockers?: WorkflowBlocker[]`.
3. **Mappers**: `apps/cockpit-v2/src/app/core/mappers.ts`
   - Implement pure, defensive mappers `extractAllowedActions` and `extractBlockers`.
   - Integrate them into `mapProjectionToSessionDetail`.
4. **Store**: `apps/cockpit-v2/src/app/core/factory.store.ts`
   - Enrich session with `actions` call in parallel with existing enrichments (`getTiming`, `getEvidence`, `getMetrics`, `getInteractions`, `getAttempts`).
   - Add public store action methods that call the HTTP service and re-fetch session data (`enrichSession` / `load()`).
5. **UI Components**:
   - `apps/cockpit-v2/src/app/features/session/session-page.component.html` & `.ts`
   - `apps/cockpit-v2/src/app/features/session/action-bar.component.ts` (or integrated subcomponents/panels for interaction replies, retry/cancel step actions, cost control, and blocker banners).
6. **Tests**:
   - `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`
   - `apps/cockpit-v2/src/app/core/mappers.spec.ts`
   - `apps/cockpit-v2/src/app/core/factory.store.spec.ts`
   - `apps/cockpit-v2/src/app/features/session/session-page.component.spec.ts` (or component specs).

---

## Detailed Step-by-Step Design

### 1. HTTP Service (`factory-api.service.ts`)

#### Models & Endpoint Contracts:
- `GET /api/factory/workflows/{id}/actions`
  - Query param: `namespaceId`
  - Headers: `X-Correlation-Id`, `X-Namespace-Id`
  - Unwraps response payload `{ data: { allowedActions: [...], blockers: [...] } }`.
  - Returns `Observable<{ allowedActions: AllowedAction[]; blockers: WorkflowBlocker[] }>`.

- `POST` Endpoints:
  - `replyInteraction`: `POST /api/factory/workflows/{id}/interactions/{interactionId}/reply`
    - Payload: `{ actionId?: string; text?: string; expectedRevision?: number }`
  - `openRetry`: `POST /api/factory/workflows/{id}/retries`
    - Payload: `{ stepId: string; expectedRevision?: number; reasonCode?: string }`
  - `cancelAttempt`: `POST /api/factory/workflows/{id}/attempts/{attemptId}/cancel`
    - Payload: `{ expectedRevision?: number; reason?: string }`
  - `continueCost`: `POST /api/factory/workflows/{id}/cost/continue`
    - Payload: `{ expectedThreshold?: number }` (optional)
  - `stopCost`: `POST /api/factory/workflows/{id}/cost/stop`
    - Payload: `{}` (or empty body)

#### Implementation details:
- Add generic `post<T>(path: string, body: unknown, namespaceId?: string, correlationId?: string): Observable<T>` helper method in `FactoryApiService` similar to `request<T>`.
- Thread `X-Correlation-Id` and `X-Namespace-Id` / query param `namespaceId`.
- Unwrap `{ data: ... }` if wrapped, and normalize errors using `normalizeError`.

---

### 2. Models (`models.ts`)

#### Interfaces:
```ts
export type AllowedActionType = 'reply' | 'retry' | 'cancel_attempt' | 'continue_cost' | 'stop_cost'

export interface AllowedAction {
  type: AllowedActionType
  label?: string
  interactionId?: string
  stepId?: string
  attemptId?: string
  caseId?: string
  questionEventId?: string
  expectedRevision?: number
  [key: string]: unknown
}

export type BlockerCode =
  | 'WAITING_HUMAN_INTERACTION'
  | 'STEP_BLOCKED'
  | 'ATTEMPT_FAILED'
  | 'REAL_COST_PAUSED'
  | 'VERIFICATION_FAILED'
  | 'UNKNOWN_RUNTIME'
  | string

export interface WorkflowBlocker {
  code: BlockerCode
  label: string
  stepId?: string
  details?: string
  [key: string]: unknown
}

export interface GetActionsResponse {
  allowedActions: AllowedAction[]
  blockers: WorkflowBlocker[]
}
```

#### SessionDetail extension:
```ts
export interface SessionDetail {
  // ... existing fields ...
  allowedActions?: AllowedAction[]
  blockers?: WorkflowBlocker[]
}
```

---

### 3. Mappers (`mappers.ts`)

- `extractAllowedActions(payload: unknown): AllowedAction[]`: Pure, defensive function mapping backend actions to `AllowedAction[]`.
- `extractBlockers(payload: unknown): WorkflowBlocker[]`: Pure, defensive function mapping backend blockers to `WorkflowBlocker[]`.
- Update `mapProjectionToSessionDetail(workflow, timing?, evidence?, metrics?, interactions?, attempts?, actions?)`:
  - Pass `actions` payload or separate `allowedActions` / `blockers`.
  - Populate `session.allowedActions` and `session.blockers` on the returned `SessionDetail`.

---

### 4. Store (`factory.store.ts`)

#### Session Enrichment:
- Extend `enrichment` cache to store `actions?: GetActionsResponse`.
- In `enrichSession(snapshot)`:
  - Add call `this.api.getActions(id, namespaceId).subscribe({ next: (actions) => merge({ actions }), error: () => undefined })`.
  - On error, degrade gracefully (keep empty array / `undefined`, do not crash store).

#### Public Store Actions:
Implement methods that call the HTTP service and then refresh session state:
- `replyInteraction(workflowId: string, interactionId: string, payload: { actionId?: string; text?: string; expectedRevision?: number }, namespaceId?: string)`
- `openRetry(workflowId: string, payload: { stepId: string; expectedRevision?: number; reasonCode?: string }, namespaceId?: string)`
- `cancelAttempt(workflowId: string, attemptId: string, payload: { expectedRevision?: number; reason?: string }, namespaceId?: string)`
- `continueCost(workflowId: string, payload?: { expectedThreshold?: number }, namespaceId?: string)`
- `stopCost(workflowId: string, namespaceId?: string)`

Each method calls the API service, subscribes, and on `next`, calls `this.load()` or `this.enrichSession(snapshot)` to refresh state from backend.

---

### 5. UI Components (`features/session/...`)

#### Invariant: Actions are rendered ONLY IF authorized in `allowedActions`.

#### Features & Controls:
1. **Blockers Section**:
   - Banner or list displaying active `blockers`.
   - Distinct visual styles based on blocker nature (e.g., amber for `WAITING_HUMAN_INTERACTION`, red for `STEP_BLOCKED` / `ATTEMPT_FAILED`, purple/blue for `REAL_COST_PAUSED`, gray for unknown/other).
2. **Interaction Reply**:
   - Conditioned on `allowedActions` with `type === 'reply'`.
   - Displays available choice buttons (`actionId`) and/or text input field for reply.
   - On submit, calls `store.replyInteraction(...)`.
3. **Retry / Cancel Attempt**:
   - Conditioned on `allowedActions` with `type === 'retry'` or `type === 'cancel_attempt'`.
   - Renders action buttons on the step or phase panel matching `stepId` / `attemptId`.
   - On click, calls `store.openRetry(...)` or `store.cancelAttempt(...)`.
4. **Cost Control (Pause / Continue / Stop)**:
   - Conditioned on `allowedActions` with `type === 'continue_cost'` or `type === 'stop_cost'`.
   - Action buttons for continuing cost or stopping cost.
   - On click, calls `store.continueCost(...)` or `store.stopCost(...)`.

---

### 6. Tests & Validation

- `factory-api.service.spec.ts`:
  - Test `getActions` unwrapping payload and query/header param forwarding.
  - Test all 5 POST methods (`replyInteraction`, `openRetry`, `cancelAttempt`, `continueCost`, `stopCost`) verifying URL, HTTP method, payload, headers, response unwrapping, and error normalization.
- `mappers.spec.ts`:
  - Test defensive mapping for `allowedActions` and `blockers`.
  - Test `mapProjectionToSessionDetail` including actions and blockers.
- `factory.store.spec.ts`:
  - Test session enrichment with `getActions`.
  - Test store action methods calling API service and re-fetching/re-enriching session.
  - Test graceful degradation when `getActions` fails.
- UI Component tests:
  - Test conditional rendering of action buttons based exclusively on `allowedActions`.

Verification Commands:
- `pnpm nx test cockpit-v2`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`
