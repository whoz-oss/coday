# Plan: Dashboard workflow run launch view wiring and backend ticket support

## Context & Objectives
The Factory Cockpit frontend currently has an orphaned/unwired `mountRunLaunchView` (`factory/dashboard/js/views/run-launch.mjs`). The "Lancer" navigation entry is missing from topbar navigation and router configuration in `app.mjs` and `cockpit.html`. Additionally, the launch contract in `run-launch.mjs` attempts a single `POST /api/factory/workflows/{id}/run` call, which fails on non-existent workflow instances because `WorkflowController.kt` requires `state == "existing"`.

Furthermore, backend support for propagating a `ticket` parameter (e.g., Jira ticket) from start/run calls through `WorkflowController.kt`, `WorkflowModels.kt`, and `SessionRunService.kt` into instance state/relations and session execution contexts needs to be established.

Finally, JS unit tests in `factory/dashboard/test/run-launch.test.mjs` (or `factory/dashboard/js/views/run-launch.test.mjs` following existing view test patterns) must be added to verify the 2-step start->run launch flow, field validation, and error handling.

---

## Scope & Changes Overview

### 1. Frontend Topbar & Navigation Wiring
- **File**: `factory/dashboard/cockpit.html`
  - Add nav link `<a href="#/launch" data-route="/launch">Lancer</a>` inside `<nav class="cockpit-nav">`.
  - Add section `<section id="view-launch" class="cockpit-view" aria-label="Lancer"><div class="panel"><h2 class="panel-title">Lancer un workflow</h2></div></section>` inside `<main id="cockpit-view-container">`.
- **File**: `factory/dashboard/js/app.mjs`
  - Import `mountRunLaunchView` from `./views/run-launch.mjs`.
  - In `ROUTES`: Add `'/launch': { id: 'view-launch', label: 'Lancer' }`.
  - In `VIEW_MOUNTERS`: Add `'/launch': mountRunLaunchView`.
  - In `onMount` handler inside `bootstrapCockpit`: Handle `route === '/launch'`:
    ```javascript
    if (route === '/launch') {
      const container = doc.getElementById('view-launch')
      if (!container) return
      mountRunLaunchView(container, {
        apiClient: api,
        namespaceId: resolveNamespaceId(win),
        onNavigate: (target, params) => navigateTo(win, target, params),
        registerTeardown: ctx.registerTeardown,
      })
      return
    }
    ```

### 2. Launch Contract Repair in `factory/dashboard/js/views/run-launch.mjs`
- Update `submit()` logic in `run-launch.mjs` to execute a 2-step creation & execution flow:
  1. **Step 1 - Create/Start instance**:
     - Determine or generate `workflowId`: generate a unique id `wf-${Date.now()}-${Math.random().toString(36).slice(2, 7)}` (or use selected `state.workflowId` if definition-based unique identifier or format required). Note: The selected definition string (e.g. `feature-session` or `workflowType`) is `workflowType`. The `workflowId` for the instance must be unique for each run, so generate a new unique `workflowId` (e.g., `wf-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`) or construct `workflowId = `${state.workflowId}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}``.
     - Construct relations map including `ticket` if `state.ticket` is non-empty: `relations = ticket ? { ticket } : undefined`.
     - Construct `start` request payload:
       ```javascript
       {
         workflow: {
           workflowId,
           workflowType: state.workflowId, // selected definition id
           title: `Run ${state.workflowId}`,
           ...(ticket ? { ticket } : {}),
           ...(relations ? { relations } : {})
         },
         execution: {
           namespaceId,
           runtimeId: 'factory-dashboard',
           kind: 'agentos',
           agentId: 'factory-agent'
         }
       }
       ```
     - Execute `POST /api/factory/workflows/${encodeURIComponent(workflowId)}/start`.
     - If `start` succeeds (or returns existing instance), proceed to Step 2.
  2. **Step 2 - Trigger run**:
     - Execute `POST /api/factory/workflows/${encodeURIComponent(workflowId)}/run` with body:
       `{ namespaceId, ...(ticket ? { ticket } : {}), ...(state.factoryRoot ? { repoRoot: state.factoryRoot } : {}) }`.
  3. **Handoff / Navigation**:
     - On successful completion, navigate to `#/detail?workflowId=${encodeURIComponent(workflowId)}&namespaceId=${encodeURIComponent(namespaceId)}`.

### 3. Backend Ticket Support in `factory-service`
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`
  - In `WorkflowStartCommand`: Add `val ticket: String? = null`.
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`
  - In `startInternal`:
    - Read `ticket` from request workflow map or top-level request:
      `val ticket = (workflow["ticket"] ?: request["ticket"]) as? String`.
    - Also ensure `ticket` is preserved inside `relations` map if `relations` is provided, or initialize `relations` with `ticket` if present:
      ```kotlin
      val rawRelations = (workflow["relations"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap() ?: mutableMapOf()
      if (!ticket.isNullOrBlank() && !rawRelations.containsKey("ticket")) {
          rawRelations["ticket"] = ticket
      }
      val command = WorkflowStartCommand(
          workflowId = workflowId,
          workflowType = workflow["workflowType"] as String,
          title = workflow["title"] as String,
          ticket = ticket,
          relations = if (rawRelations.isNotEmpty()) rawRelations else null,
      )
      ```
    - Note: Update start validation allowed keys if needed (`workflow.keys` validation in `startInternal` allows `workflowId`, `workflowType`, `title`, `relations`, `ticket`).
  - In `runInternal`:
    - Accept optional `ticket`: `val ticket = request["ticket"] as? String`.
    - Pass `ticket` to `runSession`: `runSession(caller.scope, caller.namespaceId, workflowId, Paths.get(repoRoot), operation, ticket)`.
    - Update `runSession` private helper signature and call to `sessionRunService.runSession(scope, caller.namespaceId, workflowId, Paths.get(repoRoot), ticket)`.
- **File**: `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`
  - Update `runSession` signature to accept `ticket: String? = null`:
    ```kotlin
    fun runSession(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        repoRoot: Path,
        ticket: String? = null,
    ): SessionRunResult
    ```
  - Inside `runSession`:
    - Retrieve `ticket` if passed directly or read from `instance.instance["ticket"]`, `instance.instance["relations"]`, or `startCommand`.
    - If `ticket` is present/non-null, propagate `ticket` into `nextInstance` and `projection` when persisting instance/projection state in `persistProjection`:
      `nextInstance["ticket"] = ticket` or `relations["ticket"] = ticket`.
    - Ensure `ticket` is passed along / exposed in execution context or facts if needed during step capability executions or branch/session facts.

### 4. Unit Testing
- **File**: `factory/dashboard/js/views/run-launch.test.mjs` (place next to `run-detail.test.mjs` following existing vanilla Node test runner convention `# node --test factory/dashboard/js/views/run-launch.test.mjs`).
- Test cases:
  1. **Validation tests**: Required workflow selection and required `namespaceId` validation before post calls.
  2. **2-step launch flow test**: Selecting workflow + entering namespace, factoryRoot, ticket -> verifies `POST /{workflowId}/start` call with `{ workflow, execution }` payload containing `workflowId`, `workflowType`, `title`, `ticket`, followed by `POST /{workflowId}/run` call containing `{ namespaceId, ticket, repoRoot }`.
  3. **Error handling tests**: Handles start failure or run failure gracefully and displays user feedback.
- **Backend Test Verification**:
  - Add or extend HTTP test in `WorkflowControllerHttpTest.kt` to cover `ticket` in `/start` and `/run`.

---

## Verification Plan

### Automated Tests
1. **Frontend JS Tests**:
   - Run: `node --test factory/dashboard/js/views/run-launch.test.mjs`
   - Run: `node --test factory/dashboard/js/views/run-detail.test.mjs`
2. **Backend Gradle Tests**:
   - Run: `pnpm nx test factory-service`

### Manual / Structural Inspection
- Verify route `/launch` registration and active topbar link state in `cockpit.html` and `app.mjs`.
