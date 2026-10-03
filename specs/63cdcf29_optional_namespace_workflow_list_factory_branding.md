# Executable Architecture Plan — Optional `namespaceId` on Workflows List & Cockpit Brand Renaming

## Summary
Make `namespaceId` optional on `GET /api/factory/workflows` (listing all runs within caller's `TenantScope` when missing/empty), update the cockpit client to omit empty `namespaceId`, and rename "Coday Dockyard" brand labels in `cockpit.html` to "Factory".

---

## Technical Context & Architectural Decisions

1. **Tenant Scope & Multi-Tenant Isolation**:
   - `TenantScope` (comprising `organizationId` + `workstreamId`) is resolved strictly from the verified `TrustContext` via `TenantScopeProvider.scopeOf(trustContext)`. Client input is never used to derive tenant parameters.
   - When `namespaceId` is absent or empty (e.g. query param `namespaceId` not provided, or `""` / blank string), `WorkflowService.listProjections` returns all active/removed workflow projections belonging to the caller's resolved `TenantScope` across ALL namespaces in that scope.
   - When `namespaceId` is supplied (non-blank), filtering by `namespace_id = :namespaceId` remains active.
   - No default namespace is injected or inferred.

2. **HTTP Layer Boundary (`WorkflowHttp.kt` & `WorkflowController.kt`)**:
   - In `resolveWorkflowCaller`: default parameter `requireNamespace = true` causes failure when `namespaceId` is missing/blank.
   - `list` endpoint in `WorkflowController`: call `resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)`.
   - `WorkflowCaller` receives `resolvedNamespace` which will be `null` (or empty string `""` if preferred, but typed as `String?` in updated signatures or handled with `String?`). Updating `WorkflowCaller.namespaceId` to `String?` or keeping `String?` throughout `WorkflowService` and `WorkflowRepository` makes optional namespace explicit and clean.

3. **Service & Repository Modifications (`WorkflowService`, `WorkflowRepository`, `JdbcWorkflowRepository`)**:
   - `WorkflowService.listProjections(scope: TenantScope, namespaceId: String?, state: String)`:
     - When `namespaceId.isNullOrBlank()`, query SQL without `namespace_id` condition.
     - Return envelope map: `"namespaceId" to (namespaceId ?: "")` (or omit / null depending on contract, but returning `namespaceId` parameter as-is or null keeps backward compatibility).
   - `WorkflowRepository.listProjections(scope: TenantScope, namespaceId: String?, lifecycleState: String)`:
     - Allow nullable `namespaceId: String?`.
   - `JdbcWorkflowRepository.listProjections`:
     - If `namespaceId.isNullOrBlank()`, query SQL:
       `SELECT ... FROM workflow_projections WHERE organization_id = :organizationId AND workstream_id = :workstreamId AND lifecycle_state = :lifecycleState ORDER BY workflow_id ASC`
     - If `namespaceId` is present, include `AND namespace_id = :namespaceId`.

4. **Cockpit & Frontend Updates**:
   - `factory/dashboard/js/views/projection.mjs`:
     - In `listPath(state)`: if `this.namespaceId` is missing or empty (`!this.namespaceId`), output `/api/factory/workflows?state=${state === 'removed' ? 'removed' : 'active'}` (omitting `namespaceId=` param).
     - In `load(state)` / `refresh()`: handles results with items from multiple namespaces gracefully.
   - `factory/dashboard/cockpit.html`:
     - `<title>Coday Factory Cockpit</title>` -> `<title>Factory</title>`
     - `<a class="cockpit-brand" href="#/runs" aria-label="Coday Dockyard home">` -> `aria-label="Factory home"`
     - `<span class="cockpit-brand-text">Coday Dockyard</span>` -> `<span class="cockpit-brand-text">Factory</span>`
     - Check for any remaining visible "Dockyard" text in HTML.

---

## Proposed Changes & Step-by-Step Implementation

### Step 1: Backend Domain & Persistence Layer (Optional `namespaceId`)
- **Files**:
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowHttp.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/JdbcWorkflowRepository.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt`

- **Details**:
  1. `WorkflowHttp.kt`:
     - Update `WorkflowCaller`: change `val namespaceId: String` to `val namespaceId: String?` (or ensure `resolveWorkflowCaller` handles `requireNamespace = false` by returning `null` when missing).
     - In `resolveWorkflowCaller`: when `requireNamespace = false`, do not throw `INVALID_NAMESPACE_ID` if `resolvedNamespace` is null. Return `WorkflowCaller(scope, resolvedNamespace, caseId, actorId)`.
  2. `WorkflowRepository.kt` & `JdbcWorkflowRepository.kt`:
     - Update signature `listProjections(scope: TenantScope, namespaceId: String?, lifecycleState: String)`.
     - In `JdbcWorkflowRepository.listProjections`:
       ```kotlin
       val sql = if (namespaceId.isNullOrBlank()) {
           """
           SELECT namespace_id, workflow_id, schema_version, revision, projection_hash, status,
                  projection_json, instance_json, governance_mode, definition_version, definition_hash,
                  relations_json, controller_execution, lifecycle_state
             FROM workflow_projections
            WHERE organization_id = :organizationId AND workstream_id = :workstreamId
              AND lifecycle_state = :lifecycleState
            ORDER BY workflow_id ASC
           """.trimIndent()
       } else {
           """
           SELECT namespace_id, workflow_id, schema_version, revision, projection_hash, status,
                  projection_json, instance_json, governance_mode, definition_version, definition_hash,
                  relations_json, controller_execution, lifecycle_state
             FROM workflow_projections
            WHERE organization_id = :organizationId AND workstream_id = :workstreamId
              AND namespace_id = :namespaceId AND lifecycle_state = :lifecycleState
            ORDER BY workflow_id ASC
           """.trimIndent()
       }
       val params = scopeParams(scope).addValue("lifecycleState", lifecycleState)
       if (!namespaceId.isNullOrBlank()) params.addValue("namespaceId", namespaceId)
       return jdbc.query(sql, params) { rs, _ -> readProjection(rs) }
       ```
  3. `WorkflowService.kt`:
     - Update signature `listProjections(scope: TenantScope, namespaceId: String?, state: String)`.
     - Fetch items via `repository.listProjections(scope, namespaceId, state)`.
     - In returned map: `"namespaceId" to (namespaceId ?: "")`.
  4. `WorkflowController.kt`:
     - Update `list` method:
       `val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, namespaceId, requireNamespace = false)`
       `return WorkflowDataEnvelope(service.listProjections(caller.scope, caller.namespaceId, state ?: "active"))`

### Step 2: Backend Integration Tests
- **File**: `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt`
- **Details**:
  - Add tests in `WorkflowControllerHttpTest` (subclass of `DomainIntegrationTest`):
    1. `list workflows without namespaceId returns runs across all namespaces in tenant scope`:
       - Publish workflow 1 in `namespace-1`, workflow 2 in `namespace-2` within same `TenantScope`.
       - GET `/api/factory/workflows` (no `namespaceId` param).
       - Expect HTTP 200 OK and items containing both workflow 1 and workflow 2.
    2. `list workflows with namespaceId returns filtered runs`:
       - GET `/api/factory/workflows?namespaceId=namespace-1`.
       - Expect HTTP 200 OK and items containing only workflow 1.
    3. `tenant scope isolation`:
       - Query with a different `TenantScope` (or verify another org/ws caller cannot see workflows from org-local-dev).

### Step 3: Cockpit Client Updates
- **File**: `factory/dashboard/js/views/projection.mjs`
- **Details**:
  - In `listPath(state)`:
    ```javascript
    listPath(state = this.mode) {
      const stateParam = `state=${state === 'removed' ? 'removed' : 'active'}`
      if (this.namespaceId) {
        return `/api/factory/workflows?namespaceId=${encodeURIComponent(this.namespaceId)}&${stateParam}`
      }
      return `/api/factory/workflows?${stateParam}`
    }
    ```
  - Ensures the cockpit runs view calls GET `/api/factory/workflows?state=active` without sending an empty `namespaceId=` query param.

### Step 4: Cockpit Brand Renaming
- **File**: `factory/dashboard/cockpit.html`
- **Details**:
  - `<title>Coday Factory Cockpit</title>` -> `<title>Factory</title>`
  - `aria-label="Coday Dockyard home"` -> `aria-label="Factory home"`
  - `<span class="cockpit-brand-text">Coday Dockyard</span>` -> `<span class="cockpit-brand-text">Factory</span>`
  - Ensure CSS classes (`.cockpit-brand`, `.cockpit-brand-text`) and assets (`/css/dockyard.css`) remain unchanged.

---

## Verification Plan

### Automated Tests
1. **Nx Affected Test Suite**:
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```
2. **Node Cockpit Tests**:
   ```bash
   node factory/tests/test-cockpit-shell.mjs
   ```

### Manual / Integration Verification
- Verify GET `/api/factory/workflows` without `namespaceId` returns test run projections (e.g. `test-run-1` in scope `org-local-dev` / `ws-default`).

---

## Commit Strategy
- Commit 1: `feat(factory-service): make namespaceId optional for listing workflows in TenantScope`
- Commit 2: `test(factory-service): add integration tests for multi-namespace workflow listing`
- Commit 3: `fix(cockpit): omit empty namespaceId on workflow list request`
- Commit 4: `style(cockpit): rename brand text to Factory in cockpit HTML`
