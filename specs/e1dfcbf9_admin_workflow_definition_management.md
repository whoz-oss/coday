# Implementation Plan - Admin Workflow Definition Management

Implement admin workflow definition management across `factory-service` (Kotlin/Spring) and the factory dashboard UI (`factory/dashboard/js/views/artifact-admin.mjs`). This covers:
1. Deleting a workflow definition (`DELETE /api/factory/workflow-definitions/{workflowType}/{version}`).
2. Multipart file upload for workflow definitions (`POST /api/factory/workflow-definitions/upload`).
3. Protecting `POST /api/factory/workflow-definitions` (and upload/delete endpoints) with `AdminGuard`.
4. Updating the Admin View UI (`artifact-admin.mjs`) to include a "Workflow definitions" section (upload JSON, view list, delete with confirmation modal).
5. Comprehensive Kotlin & JS unit/integration tests.

---

## Architecture & Boundary Principles
- **Authentication & Authorization**: Server-owned security. All mutation endpoints (`POST /api/factory/workflow-definitions`, `POST /api/factory/workflow-definitions/upload`, `DELETE /api/factory/workflow-definitions/{workflowType}/{version}`) must invoke `adminGuard.requireAdminRole(trustContext)` before processing. Non-admin or unauthenticated requests fail with `403 FORBIDDEN_ADMIN_REQUIRED`.
- **Tenant Scope Isolation**: `WorkflowDefinition` operations are tenant-scoped via `TenantScopeProvider` (`scope.organizationId`, `scope.workstreamId`).
- **REST Envelope Contract**: Standard `{ "data": ... }` response envelope for success, or factory error envelope `{ "error": { "code": ..., "message": ..., "details": ... } }` for exceptions.
- **Frontend Contract**: Vanilla ESM JS in `factory/dashboard/js/views/artifact-admin.mjs`. UI uses `apiClient` (`get`, `post`, `delete`) and native `<dialog>` confirmation modals for destructive deletion.

---

## User Review Required

> [!IMPORTANT]
> **API Changes**:
> - `POST /api/factory/workflow-definitions` was previously unprotected. It will now require admin credentials (`AdminGuard`).
> - New endpoint: `POST /api/factory/workflow-definitions/upload` accepting `MultipartFile` (`@RequestPart file` or `@RequestParam file`).
> - New endpoint: `DELETE /api/factory/workflow-definitions/{workflowType}/{version}`.
>
> **UI Changes**:
> - A new section "Workflow definitions" will be rendered inside `/admin` (`artifact-admin.mjs`).

---

## Proposed Changes

### 1. Persistence Layer (`factory-service`)

#### [WorkflowRepository.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt)
Add `deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean` to the interface.

```kotlin
fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean
```

#### [JdbcWorkflowRepository.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/JdbcWorkflowRepository.kt)
Implement `deleteDefinition`:
```kotlin
override fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean {
    val updated = jdbc.update(
        """
        DELETE FROM workflow_definitions
         WHERE organization_id = :organizationId
           AND workstream_id = :workstreamId
           AND workflow_type = :workflowType
           AND version = :version
        """.trimIndent(),
        scopeParams(scope)
            .addValue("workflowType", workflowType)
            .addValue("version", version),
    )
    return updated > 0
}
```

---

### 2. Service Layer (`factory-service`)

#### [WorkflowService.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt)
Add `@Transactional fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean`:
```kotlin
@Transactional
fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean {
    return repository.deleteDefinition(scope, workflowType, version)
}
```

---

### 3. Controller Layer (`factory-service`)

#### [WorkflowDefinitionController.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowDefinitionController.kt)
1. Inject `AdminGuard`:
   ```kotlin
   class WorkflowDefinitionController(
       private val service: WorkflowService,
       private val tenantScopeProvider: TenantScopeProvider,
       private val adminGuard: AdminGuard,
       private val objectMapper: ObjectMapper,
   )
   ```
2. Protect existing `register` (`POST /api/factory/workflow-definitions`):
   ```kotlin
   @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
   @Operation(summary = "Register or update a workflow definition.")
   fun register(
       @RequestBody(required = false) body: Map<String, Any?>?,
       @Parameter(hidden = true) trustContext: TrustContext?,
   ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
       adminGuard.requireAdminRole(trustContext)
       // ... existing register logic
   }
   ```
3. Add Multipart Upload endpoint (`POST /api/factory/workflow-definitions/upload`):
   ```kotlin
   @PostMapping(
       path = ["/upload"],
       consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
       produces = [MediaType.APPLICATION_JSON_VALUE],
   )
   @Operation(summary = "Upload and register a workflow definition JSON file.")
   fun uploadDefinition(
       @RequestPart("file") file: MultipartFile,
       @Parameter(hidden = true) trustContext: TrustContext?,
   ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
       adminGuard.requireAdminRole(trustContext)
       if (file.isEmpty) {
           throw workflowException(
               WorkflowErrorCodes.WORKFLOW_DEFINITION_INVALID,
               "Uploaded file is empty.",
           )
       }
       val bodyMap: Map<String, Any?> = try {
           val typeRef = object : TypeReference<Map<String, Any?>>() {}
           objectMapper.readValue(file.inputStream, typeRef)
       } catch (ex: Exception) {
           throw workflowException(
               WorkflowErrorCodes.WORKFLOW_DEFINITION_INVALID,
               "Invalid JSON file format: ${ex.message}",
           )
       }
       // Pass through existing validation & registration logic
       return registerParsedDefinition(bodyMap, trustContext)
   }
   ```
   *(Extract common register logic into a helper method `registerParsedDefinition` shared by both `register` and `uploadDefinition`)*.

4. Add Delete endpoint (`DELETE /api/factory/workflow-definitions/{workflowType}/{version}`):
   ```kotlin
   @DeleteMapping(path = ["/{workflowType}/{version}"], produces = [MediaType.APPLICATION_JSON_VALUE])
   @Operation(summary = "Delete a workflow definition by type and version.")
   fun deleteDefinition(
       @PathVariable workflowType: String,
       @PathVariable version: String,
       @Parameter(hidden = true) trustContext: TrustContext?,
   ): WorkflowDataEnvelope<Map<String, Any?>> {
       adminGuard.requireAdminRole(trustContext)
       val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
       val deleted = service.deleteDefinition(caller.scope, workflowType, version)
       if (!deleted) {
           throw workflowException(
               WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND,
               "Workflow definition was not found.",
           )
       }
       return WorkflowDataEnvelope(
           mapOf(
               "deleted" to true,
               "workflowType" to workflowType,
               "version" to version,
           ),
       )
   }
   ```

---

### 4. Dashboard View (`factory/dashboard/js/views/artifact-admin.mjs`)

Update `artifact-admin.mjs`:
1. **API Endpoints**:
   - `WORKFLOW_DEFINITIONS_PATH = '/api/factory/workflow-definitions'`
   - `WORKFLOW_DEFINITIONS_UPLOAD_PATH = '/api/factory/workflow-definitions/upload'`
   - `buildDeleteDefinitionPath(workflowType, version) = '/api/factory/workflow-definitions/' + encodeURIComponent(workflowType) + '/' + encodeURIComponent(version)`
2. **State Management**:
   Expand `createAdminState`:
   ```javascript
   definitions: {
     items: [],
     loading: false,
     uploading: false,
     deletingType: null,
     error: null,
     file: null,
   }
   ```
3. **Rendering Section**:
   Add `renderWorkflowDefinitionsSection(state)` to `renderArtifactAdmin(state)`:
   - **Upload Form**: `<input type="file" accept=".json" data-admin-definition-file="true"/>`, `<button data-admin-definition-upload="true">Upload definition</button>`.
   - **Definitions Table**: Renders `workflowType`, `version`, `definitionHash`, and a **Delete** button `<button data-admin-definition-delete="true" data-workflow-type="..." data-version="...">Supprimer</button>`.
4. **Operations & Events**:
   - `fetchDefinitions`: calls `apiClient.get('/api/factory/workflow-definitions')`. Automatically invoked on `mountArtifactAdminView`.
   - `uploadDefinition`: creates `FormData`, appends `file`, calls `apiClient.post('/api/factory/workflow-definitions/upload', formData)` (note: `apiClient` handles `FormData` correctly without forcing `Content-Type: application/json`).
   - `deleteDefinition`: uses `confirm` modal (`Purger/Supprimer la définition`), then calls `apiClient.delete(buildDeleteDefinitionPath(type, version))`, and refreshes the list on success.
   - Attach click/change listeners for file selection, upload button, refresh, and delete buttons.

---

### 5. Automated Tests

#### Kotlin Tests (`factory-service/src/test/...`)
Create `WorkflowDefinitionAdminIntegrationTest.kt` extending `PostgresContainerSpec`:
- Test 1: Non-admin caller receives `403 FORBIDDEN_ADMIN_REQUIRED` on `POST /api/factory/workflow-definitions`, `POST /api/factory/workflow-definitions/upload`, and `DELETE /api/factory/workflow-definitions/{workflowType}/{version}`.
- Test 2: Admin caller successfully registers a definition via `POST /api/factory/workflow-definitions`.
- Test 3: Admin caller successfully uploads a JSON file via `POST /api/factory/workflow-definitions/upload` (using `MockMultipartFile` or `TestRestTemplate` with multipart entity).
- Test 4: Admin caller receives `400 Bad Request` (or workflow exception response) when uploading invalid JSON or non-conforming workflow definition.
- Test 5: Admin caller successfully deletes a definition via `DELETE /api/factory/workflow-definitions/{workflowType}/{version}`. Subsequent `GET` returns 404 or missing from list.
- Test 6: Deleting non-existent definition returns `404 WORKFLOW_DEFINITION_NOT_FOUND`.

#### JavaScript Tests (`factory/dashboard/js/views/artifact-admin.test.mjs`)
Create unit test suite using `node:test`:
- Test 1: Mounting `artifact-admin` fetches workflow definitions via `apiClient.get('/api/factory/workflow-definitions')` and renders the list in the table.
- Test 2: Selecting a JSON file and clicking upload triggers `apiClient.post` with `FormData` and updates state/list upon completion.
- Test 3: Uploading an invalid definition renders an escaped error banner.
- Test 4: Clicking delete on a definition opens the confirmation dialog. On confirmation, invokes `apiClient.delete` with the correct path and refreshes definition list.
- Test 5: On server returning `403 FORBIDDEN_ADMIN_REQUIRED`, disables upload/delete buttons and shows admin error message.

---

## Verification Plan

### Automated Tests
1. **Kotlin Integration Tests**:
   Run:
   `pnpm nx test factory-service`
2. **Dashboard JS Tests**:
   Run:
   `node --test factory/dashboard/js/views/artifact-admin.test.mjs`
3. **Full NX affected test suite**:
   Run:
   `pnpm test` (or `pnpm nx affected -t test`)

### Manual Verification / Edge Cases
- Multipart file upload with non-JSON file (e.g. text/binary file) returns a clean 400 error.
- Deleting a workflow definition that has active workflow instances does not break or crash execution engine.
- Re-uploading an existing `(workflowType, version)` updates the definition hash and content seamlessly.
