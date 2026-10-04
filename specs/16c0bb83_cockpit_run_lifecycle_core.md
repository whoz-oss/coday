# Implementation Plan: Run Lifecycle Core (API + Store) in cockpit-v2

## Overview
Implement run lifecycle API methods and state management in `apps/cockpit-v2` per specification:
1. API endpoints in `FactoryApiService` (`removeWorkflow`, `restoreWorkflow`, `purgeWorkflow`) with total test coverage in `factory-api.service.spec.ts`.
2. Model exposure in `apps/cockpit-v2/src/app/core/models.ts` for resolving active attempt and revision for `stop()`.
3. State management updates in `FactoryStore`:
   - Combine active (`getWorkflows('active')`) and removed (`getWorkflows('removed')`) workflow snapshots in `load()`.
   - Derive destroyed sandboxes with `status: 'destroyed'` from removed snapshots and merge with active sandboxes into `sandboxes` signal.
   - Maintain graceful degradation on network/backend failure.
   - Implement governed `stop(workflowId)` method resolving active `attemptId` and `expectedRevision` from real loaded state. Never fabricate fallback values.
   - Implement `remove(workflowId)` and `restore(workflowId)` governed methods delegating to API and reloading state with silent error degradation.
   - Preserve `showDestroyed` / `visibleSandboxes` / `destroyedSandboxes` as the single filtering mechanism.
   - Comprehensive unit test updates in `factory.store.spec.ts`.

---

## Detailed Changes

### 1. `apps/cockpit-v2/src/app/core/factory-api.service.ts` & `factory-api.service.spec.ts`

#### Code Changes (`factory-api.service.ts`)
Add three public methods reusing `this.delete<T>()` and `this.post<T>()`:
- `removeWorkflow(workflowId: string, namespaceId?: string): Observable<unknown>`
  - Route: `DELETE /api/factory/workflows/${encodeURIComponent(workflowId)}`
  - Reuses: `this.delete<unknown>(path, namespaceId)`
- `restoreWorkflow(workflowId: string, namespaceId?: string): Observable<unknown>`
  - Route: `POST /api/factory/workflows/${encodeURIComponent(workflowId)}/restore`
  - Reuses: `this.post<unknown>(path, {}, namespaceId)`
- `purgeWorkflow(workflowId: string, namespaceId?: string): Observable<unknown>`
  - Route: `POST /api/factory/workflows/${encodeURIComponent(workflowId)}/purge`
  - Reuses: `this.post<unknown>(path, {}, namespaceId)`

Ensure `workflowId` is URL-encoded with `encodeURIComponent`.

#### Test Changes (`factory-api.service.spec.ts`)
Add a new `describe('workflow lifecycle endpoints (remove, restore, purge)')` block:
- `removeWorkflow`: sends DELETE request to `/api/factory/workflows/:id`, passes `namespaceId` in query & header when present, encodes `workflowId`, unwraps `{ data }` payload, normalizes HTTP errors.
- `restoreWorkflow`: sends POST request to `/api/factory/workflows/:id/restore` with empty object `{}` body, handles `namespaceId` and correlation headers, encodes `workflowId`.
- `purgeWorkflow`: sends POST request to `/api/factory/workflows/:id/purge` with empty object `{}` body, handles `namespaceId` and correlation headers, encodes `workflowId`.

---

### 2. `apps/cockpit-v2/src/app/core/models.ts`

Expose active attempt details so `stop()` in `FactoryStore` can inspect session/run details without introducing loose surface area:
- Verify fields on `SessionDetail` and `RunSummary`:
  `SessionDetail` already carries `attempts?: AgentAttempt[]` and `allowedActions?: AllowedAction[]`.
  `AgentAttempt` already carries `attemptId`, `stepId`, `attemptNumber`, `status`, `revision`, etc.
  `AllowedAction` already carries `type`, `attemptId`, `expectedRevision`, `stepId`.
  Check if `RunSummary` or `SessionDetail` need an active attempt helper or explicit exposure, e.g., optional `activeAttemptId?: string` and `activeAttemptRevision?: number` on `SessionDetail` or `RunSummary`, or resolve directly from `attempts` / `allowedActions` in `SessionDetail` / store.
  Exposing `activeAttemptId?: string` and `activeAttemptRevision?: number` on `SessionDetail` (or `RunSummary`) provides a clean property for resolving attempt identity and expected revision for stopping/cancelling without fabricating data.

---

### 3. `apps/cockpit-v2/src/app/core/factory.store.ts` & `factory.store.spec.ts`

#### State Loading (`load()` in `factory.store.ts`)
- Use `forkJoin` (or `combineLatest` / `zip`) to fetch `getWorkflows('active')` and `getWorkflows('removed')` concurrently:
  ```ts
  forkJoin({
    active: this.api.getWorkflows('active').pipe(catchError(() => of([] as unknown[]))),
    removed: this.api.getWorkflows('removed').pipe(catchError(() => of([] as unknown[]))),
  }).subscribe({
    next: ({ active, removed }) => this.applyWorkflows(active, removed),
    error: () => { ... }
  })
  ```
  Note: `forkJoin` with inner `catchError(() => of([]))` handles partial API failures gracefully. If the root request errors out or both fail completely, it degrades silently by clearing sandboxes/sessions/enrichment.

- Map removed workflow snapshots into `Sandbox` items with `status: 'destroyed'`:
  - Active snapshots mapped via `this.toSandbox(snapshot)` (status: `'working'` or `'idle'`).
  - Removed snapshots mapped via `this.toSandbox(snapshot)` then status forced to `'destroyed'` (or `toSandbox` handles state === `'removed'` -> status `'destroyed'`).
  - Merge active sandboxes and destroyed sandboxes into `sandboxes` signal.
  - Active workflows and removed workflows merged into `workflows` signal.
  - Sessions map updated for all snapshots (or active + removed). Enrich active runs with `enrichSession(snapshot)` as before.

- Ensure `showDestroyed` / `visibleSandboxes` / `destroyedSandboxes` computed signals remain unchanged as the ONLY source of visibility filtering.

#### Governed Actions in `factory.store.ts`

1. `stop(workflowId: string)`:
   - Must cancel the active attempt by reusing `cancelAttempt(workflowId, attemptId, { expectedRevision, reason: 'stop' }, namespaceId)` (or directly calling `api.cancelAttempt`).
   - Resolves `attemptId` and `expectedRevision` from real state:
     - Check loaded session for `workflowId`: inspect `allowedActions` (action of type `'cancel_attempt'`), or `attempts` (running/active attempt).
     - If an allowedAction of type `'cancel_attempt'` exists on the session, extract `attemptId` and `expectedRevision` from it.
     - Alternatively, find active attempt in `session.attempts` (status === `'running'`) and get its `attemptId` and `revision`.
     - If NO active `attemptId` can be resolved from real loaded state, the action is unavailable — NEVER fabricate or invent an attemptId or revision. Do nothing / log nothing / exit silently.
   - If resolved, invoke `api.cancelAttempt` with `attemptId` and `{ expectedRevision }`, then call `this.load()` on success.

2. `remove(workflowId: string)`:
   - Resolve `namespaceId` via `this.namespaceFor(workflowId)`.
   - Call `this.api.removeWorkflow(workflowId, ns)`.
   - On success (`next`), call `this.load()`.
   - On error, degrade silently (`error: () => undefined`).

3. `restore(workflowId: string)`:
   - Resolve `namespaceId` via `this.namespaceFor(workflowId)`.
   - Call `this.api.restoreWorkflow(workflowId, ns)`.
   - On success (`next`), call `this.load()`.
   - On error, degrade silently (`error: () => undefined`).

#### Tests (`factory.store.spec.ts`)
Update and add test cases in `factory.store.spec.ts`:
- Update `flushInitialWorkflows` to expect both GET `/api/factory/workflows?state=active` and GET `/api/factory/workflows?state=removed` (e.g. using `http.expectOne` for both or `http.match`).
- Test `load()` with both active and removed workflows:
  - Active snapshot -> sandbox status `'working'`/`'idle'`.
  - Removed snapshot -> sandbox status `'destroyed'`.
  - Check `destroyedSandboxes()` and `activeSandboxes()`, verify `showDestroyed` filtering behavior.
- Test `stop(workflowId)`:
  - When active attempt and revision exist in real state, sends POST `/api/factory/workflows/:id/attempts/:attemptId/cancel` with `{ expectedRevision }` and reloads.
  - When no active attempt is resolvable, does NOT issue any API request.
- Test `remove(workflowId)`:
  - Sends DELETE `/api/factory/workflows/:id`, then reloads state on success. Degrades silently on error.
- Test `restore(workflowId)`:
  - Sends POST `/api/factory/workflows/:id/restore`, then reloads state on success. Degrades silently on error.

---

## Verification Plan

### Automated Tests
Run unit tests for cockpit-v2:
`pnpm nx test cockpit-v2`

Verify all 11 test suites pass with 100% success.

---

## Constraints Checklist & Strict Rules
- Do NOT touch anything in `apps/cockpit-v2/src/app/layout/` or `apps/cockpit-v2/src/app/features/`.
- All existing public surface and governed actions (`replyInteraction`, `retry`, `cancelAttempt`, `continueCost`, `stopCost`) remain intact.
- Workflow IDs in URL paths must be encoded with `encodeURIComponent`.
- Never fabricate attempt IDs or expected revisions in `stop()`.
- Tests must pass cleanly (`pnpm nx test cockpit-v2`).
