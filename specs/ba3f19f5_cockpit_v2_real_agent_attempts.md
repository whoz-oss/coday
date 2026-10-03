# Implementation Plan - Real Agent Execution Attempts in Cockpit v2

Fetch real agent execution attempts from backend `GET /api/factory/workflows/{workflowId}/attempts` in `apps/cockpit-v2`, map them to `PhaseDetail` and `SessionDetail`, and eliminate hardcoded `'0/0'` attempt string.

## Proposed Changes

### 1. `apps/cockpit-v2/src/app/core/models.ts`

- Export interface `AgentAttempt` matching backend `DurableAgentAttemptDto`:
  ```ts
  export interface AgentAttempt {
    attemptId: string
    stepId: string
    attemptNumber: number
    agentName: string
    status: string
    caseId: string
    failureCode?: string
    resultEvidenceId?: string
    revision?: number
    createdAt?: string
    startedAt?: string
    completedAt?: string
  }
  ```
- Enrich `PhaseDetail`:
  - Keep existing `attempt: string` (for backwards compatibility).
  - Add optional structured fields:
    `currentAttemptNumber?: number`
    `totalAttempts?: number`
    `attempts?: AgentAttempt[]`
- Enrich `SessionDetail`:
  - Add optional field `attempts?: AgentAttempt[]`.

### 2. `apps/cockpit-v2/src/app/core/factory-api.service.ts`

- Add method `getAttempts(workflowId: string, namespaceId?: string): Observable<unknown[]>`
- Implementation follows `getInteractions`/`getMetrics`:
  - Perform `GET /api/factory/workflows/:id/attempts` with query param / header `namespaceId` if provided.
  - Unwrap `{ data: [...] }` or `{ data: { items: [...] } }` or raw array, fallback to `[]`.
  - Handle errors via standard `catchError`.

### 3. `apps/cockpit-v2/src/app/core/mappers.ts`

- Export pure helper function `extractAttempts(payload: unknown): AgentAttempt[]`:
  - Defensive handling for null/undefined/non-array payload yielding `[]`.
  - Map fields safely (`attemptId`, `stepId`, `attemptNumber`, `agentName`, `status`, `caseId`, `failureCode`, `resultEvidenceId`, `revision`, `createdAt`, `startedAt`, `completedAt`).
- Update `buildPhaseDetail`:
  - Signature: `buildPhaseDetail(steps: unknown[], fallbackStatus: RunStatus, interactionsCount = 0, attempts: AgentAttempt[] = []): PhaseDetail`
  - Identify active/current step (`active = steps.find(step => resolveStepState(step) === 'active') ?? steps[steps.length - 1]`).
  - Extract step ID: `stepId = getString(obj, 'id') ?? getString(obj, 'key')`.
  - Filter `stepAttempts = attempts.filter(a => a.stepId === stepId)`.
  - If `stepAttempts` is non-empty:
    - Determine current attempt (`currentAttempt` = item with maximum `attemptNumber` or latest timestamp).
    - Format `attemptStr = `${currentAttempt.attemptNumber}/${stepAttempts.length}``.
    - Populate `attempt: attemptStr`, `currentAttemptNumber: currentAttempt.attemptNumber`, `totalAttempts: stepAttempts.length`, `attempts: stepAttempts`.
  - If `stepAttempts` is empty:
    - Replace hardcoded `'0/0'` with `'1/1'` default (eliminating `'0/0'`).
  - Populate agent name (`agentName`), status (`status`), `caseId`, and optional `failureCode` on `PhaseDetail` or sections where applicable.
- Update `mapProjectionToSessionDetail`:
  - Extend parameter list / optional arguments to accept `attempts?: unknown`.
  - Unwrap via `extractAttempts(attempts)`.
  - Pass mapped attempts to `buildPhaseDetail(steps, status, mappedInteractions.length, mappedAttempts)`.
  - Include `attempts: mappedAttempts` in returned `SessionDetail`.

### 4. `apps/cockpit-v2/src/app/core/mock-data.ts`

- Update `SESSION_872641A8.phase.attempt` from `'0/0'` to `'1/1'`.

### 5. `apps/cockpit-v2/src/app/core/factory.store.ts`

- In `enrichment` state map in `FactoryStore`, add `attempts?: unknown`.
- In `enrichSession(snapshot)`, invoke `this.api.getAttempts(id, namespaceId).subscribe({ next: (attempts) => merge({ attempts }), error: () => undefined })`.
- Ensure silent failure handling (graceful degradation) if API call fails.

### 6. Tests (`apps/cockpit-v2/src/app/core/*.spec.ts`)

- In `factory-api.service.spec.ts`: add tests for `getAttempts(workflowId, namespaceId)` verifying envelope unwrapping, raw array handling, and parameter passing.
- In `mappers.spec.ts`: add tests for `extractAttempts`, test `buildPhaseDetail` and `mapProjectionToSessionDetail` with active steps having multiple attempts, zero attempts, missing fields, and confirm `'0/0'` is never produced.
- In `factory.store.spec.ts`: verify fetching and merging of `attempts` into session details, as well as silent degradation on HTTP errors.

## Verification Plan

### Automated Tests
- `pnpm nx test cockpit-v2`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`

### Code Search Check
- Ensure `"0/0"` string does not exist in any file in `apps/cockpit-v2`.
