# Plan: Launch Workflow (Run) in Cockpit V2

## Architectural Overview

This plan details the implementation of the ability to **LAUNCH** a workflow run in Cockpit V2 (`apps/cockpit-v2`), connecting directly to real `factory-service` backend REST endpoints (`/api/factory/workflows/{workflowId}/start`, `/api/factory/workflows/{workflowId}/run`, `/api/factory/workflow-definitions`, and `/api/namespaces`).

The workflow launch process is a two-step sequence executed by the client upon form submission:
1. `POST /api/factory/workflows/{workflowId}/start` — Materializes the workflow instance from a definition. If the backend returns `WORKFLOW_IDENTITY_CONFLICT` or `WORKFLOW_ALREADY_EXISTS` (or HTTP 409 conflict), the error is ignored so the run phase can proceed for existing instances.
2. `POST /api/factory/workflows/{workflowId}/run` — Triggers the actual durable async execution (HTTP 202 Accepted with `{ data: { status: "accepted", submissionId: "..." } }`).

---

## Targeted Files & Changes

### 1. `apps/cockpit-v2/src/app/core/factory-api.service.ts`

#### Added Types/Interfaces:
```typescript
export interface StartWorkflowRequest {
  workflow: {
    workflowId: string
    workflowType: string
    title: string
    ticket?: string
  }
  execution: {
    namespaceId: string
    runtimeId: string // e.g. 'factory-dashboard'
    kind: string      // e.g. 'agentos'
    agentId: string   // e.g. 'factory-agent'
  }
  controllerRequest: string
}

export interface RunWorkflowRequest {
  namespaceId: string
  ticket?: string
  repoRoot?: string
}

export interface RunWorkflowResponse {
  status?: string
  submissionId?: string
  workflowId?: string
  [key: string]: unknown
}

export interface NamespaceItem {
  id?: string
  name?: string
  namespaceId?: string
  [key: string]: unknown
}
```

#### Added Methods:
- `startWorkflow(workflowId: string, payload: StartWorkflowRequest, namespaceId?: string): Observable<unknown>`
  - Issues `POST /api/factory/workflows/${encodeURIComponent(workflowId)}/start` using `this.post(...)`.
- `runWorkflow(workflowId: string, payload: RunWorkflowRequest, namespaceId?: string): Observable<RunWorkflowResponse>`
  - Issues `POST /api/factory/workflows/${encodeURIComponent(workflowId)}/run` using `this.post(...)`.
- `getNamespaces(): Observable<NamespaceItem[]>`
  - Issues `GET /api/namespaces` using `this.request(...)`.
  - Catches errors with `catchError(() => of([]))` to gracefully degrade to `[]` if the endpoint is unavailable, missing, or returns an error.
  - Unwraps payload array or `.items` array defensively (similar to `getWorkflows`).

#### Method Preservation:
- Preserve **ALL** existing methods: `getWorkflows`, `getWorkflow`, `getTiming`, `getEvidence`, `getMetrics`, `getInteractions`, `getAttempts`, `getActions`, `replyInteraction`, `openRetry`, `cancelAttempt`, `continueCost`, `stopCost`, `runGarbageCollection`, `purgeArtifact`, `setLegalHold`, `getWorkflowDefinitions`, `uploadWorkflowDefinition`, `deleteWorkflowDefinition`.

---

### 2. Form & Standalone Component: `apps/cockpit-v2/src/app/features/launch/launch-page.component.ts` (.html, .scss, .ts)

#### Component Structure:
- Path: `apps/cockpit-v2/src/app/features/launch/launch-page.component.ts`
- Associated HTML (`launch-page.component.html`) and SCSS (`launch-page.component.scss`).
- Standalone component using Angular Material (`MatFormFieldModule`, `MatSelectModule`, `MatInputModule`, `MatButtonModule`, `MatIconModule`, `MatProgressSpinnerModule`, etc.) + `ReactiveFormsModule`, `RouterLink`.

#### Form Definition (ReactiveForms):
```typescript
form = this.fb.group({
  workflowType: ['', Validators.required],
  namespaceId: ['', Validators.required],
  controllerRequest: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(4000)]],
  repoRoot: [''],
  ticket: ['']
})
```

#### Lifecycle & Initialization:
- In constructor / initialization:
  - Update `ShellState.crumbs`: `[{ label: 'Sandboxes', url: '/sandboxes' }, { label: 'Lancer un run' }]`.
  - Fetch workflow definitions via `this.api.getWorkflowDefinitions()`. Parse `.items` / array to extract unique `workflowType` entries. If loading fails, handle error and show a user-friendly message.
  - Fetch namespaces via `this.api.getNamespaces()`. If array is non-empty, auto-select first or let user select. If empty/error, allow free text entry or select fallback options (e.g. `'default'`).

#### Submission Logic (`onSubmit()`):
1. Check `form.valid`. If invalid, mark all as touched and return.
2. Set `submitting.set(true)`, clear previous error messages.
3. Generate a unique `workflowId`: e.g. `wf-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`.
4. Construct `StartWorkflowRequest`:
   ```typescript
   const startPayload: StartWorkflowRequest = {
     workflow: {
       workflowId,
       workflowType,
       title: `Run ${workflowType}`,
       ...(ticket ? { ticket } : {})
     },
     execution: {
       namespaceId,
       runtimeId: 'factory-dashboard',
       kind: 'agentos',
       agentId: 'factory-agent'
     },
     controllerRequest
   }
   ```
5. Call `this.api.startWorkflow(workflowId, startPayload, namespaceId)`:
   - Handle response via RxJS / `catchError`: if error code is `WORKFLOW_IDENTITY_CONFLICT`, `WORKFLOW_ALREADY_EXISTS`, or status `409`, log warning and continue to step 6 (return `of(null)`). For other errors, rethrow or handle error.
6. Call `this.api.runWorkflow(workflowId, runPayload, namespaceId)`:
   ```typescript
   const runPayload: RunWorkflowRequest = {
     namespaceId,
     ...(ticket ? { ticket } : {}),
     ...(repoRoot ? { repoRoot } : {})
   }
   ```
7. On HTTP 200/202 Success:
   - Show success message / banner ("Lancement accepté (id: submissionId)").
   - Trigger store reload / refresh: `this.store.refresh()` (or `this.store.load()` - see Store section).
   - Navigate to `/sessions/${workflowId}` (or `/sandboxes`).
8. On Error (4xx/5xx/validation):
   - Set readable error signal/banner (`error.message` or `error.code`).
   - Stop spinner, do not navigate, do not fabricate success.

---

### 3. Entry Point in Sandboxes Screen (`sandboxes-page.component.ts` & `.html`)

- Add a prominent "Lancer un run" / "Nouveau run" button in the header or primary action area of `sandboxes-page.component.html`.
- Use `<a mat-flat-button class="sf-primary" routerLink="/lancer"><mat-icon>play_arrow</mat-icon> Lancer un run</a>`.
- Clean up or adapt the legacy informational "Nouvelle sandbox" panel: replace or add a clear callout directing users to click "Lancer un run" to start a real workflow.

---

### 4. Routes (`apps/cockpit-v2/src/app/app.routes.ts`)

Add route:
```typescript
{
  path: 'lancer',
  loadComponent: () => import('./features/launch/launch-page.component').then((m) => m.LaunchPageComponent)
}
```
Preserve existing routes: `''`, `sandboxes`, `sessions/:runId`, `historique`, `reglages`, `**`.

---

### 5. Store (`apps/cockpit-v2/src/app/core/factory.store.ts`)

- Expose a public method `refresh(): void` or make `load(): void` public:
```typescript
/** Public handle to trigger a re-fetch of active workflow projections. */
refresh(): void {
  this.load()
}
```

---

### 6. Tests & Validation Specs

#### `factory-api.service.spec.ts`
- Add unit tests for `startWorkflow`:
  - Verify POST path `/api/factory/workflows/{workflowId}/start`, headers (`X-Correlation-Id`, `X-Namespace-Id`), query params (`namespaceId`), and body structure.
- Add unit tests for `runWorkflow`:
  - Verify POST path `/api/factory/workflows/{workflowId}/run`, handling 202 Accepted response payload.
- Add unit tests for `getNamespaces`:
  - Verify GET `/api/namespaces` unwraps array / `.items`.
  - Verify graceful fallback to `[]` on HTTP 404/500/network error.

#### `launch-page.component.spec.ts` (New file)
- Test form initialization, initial definitions/namespaces loading.
- Test form validation rules (`controllerRequest` length 1-4000, required fields).
- Test successful launch flow:
  - Form submission triggers `startWorkflow` then `runWorkflow`.
  - Verify conflict error on `startWorkflow` is ignored and `runWorkflow` proceeds.
  - Verify store `refresh` and router navigation on successful launch.
- Test error handling:
  - Verify API error displays banner message without crashing or navigating.

---

## Verification Plan

Run the suite of checks:
1. `pnpm nx test cockpit-v2` — All tests pass (including new specs).
2. `pnpm nx lint cockpit-v2` — Lint checks pass.
3. `pnpm nx build cockpit-v2` — Angular build compiles without errors.
