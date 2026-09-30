# Admin workflow definition management

## What changed

The factory workflow-definition registry now has an admin-only mutation surface for registering, uploading, and deleting definitions. The existing JSON registration endpoint is gated with `AdminGuard`; a new multipart endpoint accepts a JSON file, parses it with Jackson, and sends the parsed map through the existing `WorkflowDefinitionValidator` and registration path. Empty files, malformed JSON, and invalid definitions produce readable workflow errors; invalid-definition error codes are mapped to HTTP 400.

New endpoint contracts:

- `POST /api/factory/workflow-definitions` — existing JSON register/upsert endpoint, now admin-only.
- `POST /api/factory/workflow-definitions/upload` — admin-only multipart upload with part name `file`; format is JSON workflow-definition v1.
- `DELETE /api/factory/workflow-definitions/{workflowType}/{version}` — admin-only deletion by definition identity. Missing definitions return `WORKFLOW_DEFINITION_NOT_FOUND`; successful responses report `deleted`, type, and version.

Deletion remains tenant-scoped through the resolved `TenantScope`, and is implemented in the JDBC workflow-definition table. The change adds no Neo4j or execution-engine persistence path.

## Where it lives

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowDefinitionController.kt` adds admin checks, multipart parsing, shared validation/registration handling, and the definition DELETE route.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt` exposes transactional `deleteDefinition`.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt` and `JdbcWorkflowRepository.kt` add the tenant-scoped JDBC delete operation, returning whether a row was removed.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowExceptions.kt` adds `WORKFLOW_DEFINITION_INVALID` and maps definition-validation failures to 400.
- `factory/dashboard/js/views/artifact-admin.mjs` adds the Workflow definitions section inside the existing admin view. It loads definitions on mount, renders type/version/hash rows, captures a JSON file, posts it as `FormData`, refreshes after upload/delete, and asks for confirmation before deleting. It uses the existing admin entitlement/error handling, so a `403 FORBIDDEN_ADMIN_REQUIRED` disables the mutation controls.

## Verification

The Kotlin integration suite in `factory-service/src/test/kotlin/io/whozoss/factory/workflow/web/WorkflowDefinitionAdminIntegrationTest.kt` covers non-admin 403 responses for all mutations, admin registration, upload, list/get, malformed JSON and validation rejection, successful deletion, and deletion of an unknown definition.

The Node test suite in `factory/dashboard/js/views/artifact-admin.test.mjs` covers initial listing/rendering, escaped values and errors, `FormData` upload, confirmation and DELETE path construction, refresh behavior, declined confirmation, admin gating, and unmount cleanup.

Run the focused checks with:

```sh
pnpm nx test factory-service
node --test factory/dashboard/js/views/artifact-admin.test.mjs
```
