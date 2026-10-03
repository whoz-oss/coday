# Cockpit Factory Simplification and Optional Namespace Plan

## Problem Statement
In the cockpit on branch `feature/benjamin/software-factory-v2`:
1. The cockpit navigation and routes create confusion:
   - `/runs` (tab "Runs") is a static empty placeholder section.
   - `/projection` (tab "Projection") renders the true workflow session list with multi-lane swimlanes (HUMAIN / AGENT / CODE).
   - UX Goal: Make the workflow list the default home screen (`/runs` or `/`), removing/redirecting the empty placeholder tab, while preserving `/launch`, `/forge`, `/admin`.
   - Interaction Goal: Clicking a workflow run in the list should open its timeline in swimlanes. Either via a dedicated detail view (`/detail`) or a clear expansion using existing `temporal-lanes.mjs` and `workflow-card.mjs` components.

2. Technically, optional `namespaceId` fails in local dev loopback (`GET /api/factory/workflows` returns `401 INVALID_NAMESPACE_ID`):
   - In `factory-service`, `WorkflowHttp.kt`'s `resolveWorkflowCaller` defaults `requireNamespace = true`. `WorkflowController.list()` calls `resolveWorkflowCaller(..., requireNamespace = false)`, but `WorkflowService.listProjections` defaults `namespaceId` to `""` when missing, causing repository queries to filter `namespace_id = ""` and return 0 items.
   - Furthermore, `WorkflowSseController.stream` in `WorkflowSseController.kt` calls `resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)` with `requireNamespace = true` by default, throwing `INVALID_NAMESPACE_ID` when `namespaceId` is absent in SSE stream requests.
   - In the frontend (`projection.mjs`), absent `namespaceId` should omit `namespaceId` parameters from `listPath()`, `detailPath()`, `lifecyclePath()`, `fetchTiming()` and SSE `streamUrl`, rather than passing empty `namespaceId=` strings.

3. Mandatory Regression Test:
   - Must add an HTTP test reproducing `GET /api/factory/workflows` without `namespaceId` under a dev loopback `TrustContext` without `namespaceId`, asserting 200 status with tenant-scoped workflows (never 401 / `INVALID_NAMESPACE_ID`). Also assert that when `namespaceId` is provided, filtering operates correctly.

---

## Architectural Analysis & Proposed Changes

### Component 1: Frontend Cockpit Restructuring (`factory/dashboard/`)

1. **`factory/dashboard/cockpit.html`**:
   - Update topbar navigation links:
     - Change `<a href="#/runs">Runs</a>` to target `#/runs` or `#/` as home. Retain navigation items: `Runs` (which mounts the workflow list/projection view), `Lancer` (`#/launch`), `Forge` (`#/forge`), `Admin` (`#/admin`).
     - Remove or keep `Détail` (`#/detail`) depending on routing design, or update `Runs` tab label to represent the home workflow list.
     - Ensure brand link `href="#/runs"` loads the main workflow list view.
   - Update `#view-runs` placeholder section: replace static placeholder HTML with the main list container (or align container IDs so `#view-runs` mounts `mountProjectionView`).

2. **`factory/dashboard/js/app.mjs`**:
   - Make `mountProjectionView` the mounter for `/runs` (and/or default route `/` / `/runs`):
     - In `ROUTES`, update `/runs` label / mapping, or redirect `/runs` to mount `mountProjectionView`.
     - In `VIEW_MOUNTERS`: map `/runs` and `/projection` to `mountProjectionView` (or make `/runs` point to `mountProjectionView`).
     - In `bootstrapCockpit`: update `onMount` logic for route `/runs` (and `/projection` if aliased/retained) so that `mountProjectionView` is initialized into `#view-runs` (or `#view-projection`).
   - Detail Navigation / Click handling:
     - On workflow card click (or dedicated detail link/click in `workflow-card.mjs` / `projection.mjs`), navigate to `#/detail?workflowId=<id>` (if `namespaceId` present, append `&namespaceId=<ns>`).
     - Map `/detail` in `VIEW_MOUNTERS` or `bootstrapCockpit` `onMount` to invoke `mountRunDetailView` (`factory/dashboard/js/views/run-detail.mjs`), which already exists and renders `gantt.mjs`, phase panels, facts, and timing!
     - Alternatively, if clicking on a card in the list view expands or navigates to `/detail`, ensure smooth UX without reinventing components (`workflow-card.mjs` and `temporal-lanes.mjs` / `gantt.mjs` / `run-detail.mjs`).

3. **`factory/dashboard/js/views/projection.mjs`**:
   - Fix URL query param composition when `namespaceId` is null/empty:
     - `listPath()`: omit `namespaceId` if missing (already checks `if (this.namespaceId)` -> `/api/factory/workflows?state=...`).
     - `detailPath(workflowId)`: if `this.namespaceId` is present, include `?namespaceId=${this.namespaceId}`; if missing, return `/api/factory/workflows/${encodeURIComponent(workflowId)}`.
     - `lifecyclePath(action, workflowId)`: if `this.namespaceId` is present, append `?namespaceId=${this.namespaceId}`; otherwise omit query param or append action endpoint directly (`/api/factory/workflows/${encodeURIComponent(workflowId)}/restore`).
     - `fetchTiming(workflowId)`: if `this.namespaceId` is present, include `?namespaceId=...`; if missing, query `/api/factory/workflows/${encodeURIComponent(workflowId)}/timing`.
     - `streamUrl`: if `this.namespaceId` is present, use `/api/factory/workflows/stream?namespaceId=${encodeURIComponent(this.namespaceId)}`; if missing, use `/api/factory/workflows/stream` (without query param).
   - In card rendering or group rendering, wire click on card header / card body / "Détail" button to trigger navigation (`onNavigate('/detail', { workflowId: id, ...(namespaceId ? { namespaceId } : {}) })`).

---

### Component 2: Backend Backend Fixes (`factory-service`)

1. **`factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseController.kt`**:
   - In `stream(...)`:
     - Change `resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId)` to `resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)`.
     - `WorkflowSseHub.register(...)`: `caller.namespaceId` will be `""` when no namespace was specified. `WorkflowSseHub` supports registering connections under `""` or broadcasting appropriately.

2. **`factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`**:
   - In `listProjections(scope, namespaceId, state)`:
     - `resolvedNamespace` is `namespaceId?.takeIf { it.isNotBlank() }` (which evaluates to `null` when missing).
     - Return map: map `"namespaceId"` to `(resolvedNamespace ?: "")`.
     - Repository call: `repository.listProjections(scope, resolvedNamespace, state)`.

3. **`factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/JdbcWorkflowRepository.kt`**:
   - In `listProjections(scope, namespaceId, lifecycleState)`:
     - Verify SQL query: `val namespaceFilter = if (namespaceId.isNullOrBlank()) "" else " AND namespace_id = :namespaceId"`.
     - When `namespaceId` is `null` or `""`, `namespaceFilter` is `""`, executing `WHERE organization_id = :organizationId AND workstream_id = :workstreamId AND lifecycle_state = :lifecycleState`. This returns ALL workflows in the tenant scope regardless of `namespace_id`.

4. **`factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`**:
   - Verify `list` calls `resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)`.

---

### Component 3: Test Suite & Non-Regression (`WorkflowControllerHttpTest.kt` & `WorkflowSseHttpTest.kt`)

1. **`factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt`**:
   - Add test: `list without namespaceId on loopback trust context returns 200 OK with all tenant workflows and never 401 INVALID_NAMESPACE_ID`.
   - Add test: `list with explicit namespaceId filters correctly to only that namespace`.
   - Verify that test assertions check status code is `HttpStatus.OK` (200), that returned items contain expected workflow IDs published under different namespaces within the same tenant scope.

2. **`factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowSseHttpTest.kt`** (or new test case):
   - Test `GET /api/factory/workflows/stream` without `namespaceId` parameter under loopback context returns `200 OK` (or SSE emitter) without throwing `401 / INVALID_NAMESPACE_ID`.

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Fix Backend Optional Namespace Handling
- Edit `WorkflowSseController.kt`:
  Pass `requireNamespace = false` to `resolveWorkflowCaller`.
- Review `WorkflowController.kt`:
  Confirm `list()` passes `requireNamespace = false`.
- Review `WorkflowService.kt` and `JdbcWorkflowRepository.kt`:
  Ensure `null` / `""` namespace queries ALL records for the `TenantScope`.

### Step 2: Add Backend HTTP Non-Regression Tests
- In `WorkflowControllerHttpTest.kt`:
  - Add explicit test case executing `GET /api/factory/workflows` with loopback TrustContext and NO `namespaceId` query param. Assert status `200 OK` and verify all workflows for the scope are returned.
  - Add test case with explicit `namespaceId` query param. Assert status `200 OK` and verify only matching namespace workflows are returned.
- Run tests via `pnpm nx test factory-service`.

### Step 3: Frontend Cockpit View Consolidation
- Edit `factory/dashboard/cockpit.html`:
  - Consolidate navigation: `Runs` (`#/runs`), `Lancer` (`#/launch`), `Forge` (`#/forge`), `Admin` (`#/admin`).
  - Set default active view container `#view-runs`.
- Edit `factory/dashboard/js/app.mjs`:
  - Update `ROUTES` and `VIEW_MOUNTERS` so `/runs` and default route load `mountProjectionView` into `#view-runs`.
  - Wire `/detail` to load `mountRunDetailView` (from `views/run-detail.mjs`) into `#view-detail`.
  - Ensure navigation transitions clean up previous view teardowns properly.
- Edit `factory/dashboard/js/views/projection.mjs`:
  - Update `listPath()`, `detailPath()`, `lifecyclePath()`, `fetchTiming()`, and `streamUrl` logic to completely omit `namespaceId` when `this.namespaceId` is falsy/blank.
  - Update card interaction: clicking on a workflow card navigates to `#/detail?workflowId=<id>` (plus `&namespaceId=<ns>` if `namespaceId` is present).

### Step 4: Verification & Manual Validation
- Run full build and tests:
  `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
  or
  `cd agentos && ./gradlew check` and `./gradlew test` in root/factory-service.
- Manual validation procedure:
  1. Start factory service: `./gradlew :factory-service:bootRun` (or run server).
  2. Open browser at `http://127.0.0.1:8141/`.
  3. Verify the home page directly renders the Runs list (workflow session projections with HUMAIN / AGENT / CODE swimlanes).
  4. Verify no `INVALID_NAMESPACE_ID` errors occur in console or network tab.
  5. Click on a workflow run (e.g. `test-run-1`) -> observe transition to the run detail timeline view with swimlanes.

---

## Artifacts Created / Modified
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/sse/WorkflowSseController.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/JdbcWorkflowRepository.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt`
- `factory/dashboard/cockpit.html`
- `factory/dashboard/js/app.mjs`
- `factory/dashboard/js/views/projection.mjs`
- `specs/c6d71268_simplify_cockpit_optional_namespace.md`
