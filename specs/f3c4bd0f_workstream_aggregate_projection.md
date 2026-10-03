# Phase 5 — Workstream Aggregate & Bounded Aggregated Projection

## Goal

Enrich the `workstream` aggregate in `factory-service` with a versioned registry and
add a **read-only** aggregated projection that composes live state owned by other
subsystems (workflows, attempts, oracles, environments, human interactions) without
duplicating or mutating any of it.

## HARD BOUNDARY (do not cross)

- Create/modify Kotlin **only** under:
  - `factory-service/src/main/kotlin/io/whozoss/factory/workstream/`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workstream/`
- You may **read from / inject** beans owned by other packages, but must **not edit**
  any file outside `workstream/`. Specifically do NOT touch `agentattempt`, `workflow`,
  `oracle`, `environment`, `persistence`, `web`, `error`, migrations, or the release pipeline.
- Consume other aggregates **only** through their already-public injectable beans
  (listed below). Never reach into their persistence except via the read-only
  `Neo4jClient` Cypher described for oracles (reads existing nodes, writes nothing).

---

## Repo facts the builder must rely on (verified)

### Workstream package (current state — all will be evolved, not duplicated)
`factory-service/src/main/kotlin/io/whozoss/factory/workstream/`
- `WorkstreamService.kt` — `@Service`. Methods today:
  - `fun list(scope: TenantScope): List<Map<String, Any?>>`
  - `fun create(scope, slug, name, status): Map<String, Any?>` (validates slug regex
    `^[a-z0-9]+(?:-[a-z0-9]+)*$`, rejects blanks → `INVALID_WORKSTREAM_REQUEST` /
    `INVALID_WORKSTREAM_SLUG`, duplicate → `WORKSTREAM_ALREADY_EXISTS` via `ConflictException`).
- `WorkstreamController.kt` — `@RestController @RequestMapping("/api/factory/workstreams")`.
  `GET` (list) and `POST` (create). Uses `resolveFactoryCaller(trustContext, tenantScopeProvider)`
  and `requireNamespaceQuery(namespaceId)`.
- `persistence/WorkstreamNode.kt` — `@Node("Workstream")`, `@Id id` =
  `compositeId(organizationId, workstreamId)` = `"$organizationId|$workstreamId"`.
  Fields: `id, organizationId, workstreamId, name, status, revision=1, createdAt, updatedAt`.
- `persistence/Neo4jWorkstreamRepository.kt` — `@Repository @Primary`,
  implements `ScopedRepository<Map<String,Any?>, String>`. Methods: `findById`, `list`,
  `create`, `deleteById`, private `toView()` → `{slug,name,status,revision}`.
  **Note:** registry is keyed only by `organizationId` + the slug (`scope.workstreamId`
  is NOT part of the composite id today — the slug is the path/business id).
- `persistence/SpringDataNeo4jWorkstreamRepository.kt` — `Neo4jRepository<WorkstreamNode,String>`
  with `findAllByOrganization(organizationId): List<WorkstreamNode>` (ordered by `workstreamId`).

### Shared infra (read-only; already injectable)
- `persistence/TenantScope` = `data class TenantScope(organizationId: String, workstreamId: String)`.
- `persistence/TenantScopeProvider` (`@Component`): `scopeOf(trustContext): TenantScope?`
  (fail-closed), `defaultScope()`.
- `persistence/ScopedRepository<T,ID>`: `findById(scope,id)`, `deleteById(scope,id)`.
- `web/FactoryHttp.kt`:
  - `data class FactoryCaller(scope: TenantScope, actorId, authorityId, externalUserId)`.
  - `resolveFactoryCaller(trustContext, tenantScopeProvider): FactoryCaller` — 401
    `TRUST_CONTEXT_UNAVAILABLE` when scope cannot be resolved.
  - `requireNamespaceQuery(namespaceId: String?): String` — 400 `MISSING_NAMESPACE_ID`.
  - `data class FactoryDataEnvelope<T>(val data: T)` — the `{ "data": ... }` success envelope.
  - `factoryError(statusCode, code, message, details?): Nothing` throws `FactoryHttpException`.
- `web/TrustContext` — fields include `organizationId`, `workstreamId`, `namespaceId`,
  `principalId`, `scopes`, `authenticated`, etc.
- `error/` — error envelope is `{ "error": { "code", "message", "details" } }` produced by
  `FactoryExceptionHandler` (`@RestControllerAdvice`). Reusable exceptions:
  `BadRequestException`(400 `BAD_REQUEST`), `ConflictException`(409 `CONFLICT`),
  `RevisionConflictException`(409 `REVISION_CONFLICT`),
  `ResourceNotFoundException`(404 `NOT_FOUND`), `UnprocessableEntityException`(422),
  `ForbiddenAdminRequiredException`(403), `UnauthenticatedException`(401).
  All take `(message, details: Any? = null, cause?)`. The `errorCode` is fixed per class;
  the slug-level codes (e.g. `INVALID_WORKSTREAM_SLUG`) are passed today as the `details`
  map `mapOf("code" to ...)`. **For new boundary errors, prefer `factoryError(status, CODE, msg)`
  so the machine `code` is the envelope `code`** (matches `FactoryHttpException`).
- `web/FactoryHttp.FactoryHttpException(statusCode, errorCode, message, details?)` lets you
  emit any status + machine code directly.
- `config/Neo4jPersistenceConfiguration` exposes a `Neo4jClient` bean
  (`org.springframework.data.neo4j.core.Neo4jClient`) and a `Driver` bean — injectable.
  Read pattern (see `Neo4jSchemaInitializer`): `neo4jClient.query("MATCH ... RETURN ...").bindAll(mapOf(...)).fetch().all()`.

### Consumable beans for the projection (inject; never modify)
- **Workflows** — `workflow/service/WorkflowService.kt` (`@Service`):
  - `listProjections(scope, namespaceId: String?, state: String): Map<String,Any?>` —
    `state` must be `"active"` or `"removed"` (else throws). Returns
    `{namespaceId, state, items: List<Map>}`. Each item carries `workflowId`,
    `namespaceId`, `revision`, `projectionHash`, and `projection` (the full
    WorkflowProjection map: `{schemaVersion, workflowId, workflowType, title, status, steps[]}`).
    Each `steps[]` entry has `{id, name, status, ...}`.
  - `getProjection(scope, namespaceId, workflowId): Map<String,Any?>`.
  - `listInteractions(scope, namespaceId, workflowId, openOnly: Boolean): WorkflowHttpResult`
    — `WorkflowHttpResult(status: Int, data: Any?)`; `data` = `{namespaceId, workflowId, items: List<Map>}`.
  - Step status vocabulary (`WorkflowStatuses`): `pending, ready, running, waiting_human,
    blocked, completed, failed, cancelled`.
- **Attempts** — `agentattempt/service/DurableAgentAttemptService.kt` (`@Service`):
  - `findByWorkflow(scope: TenantScope, namespaceId: String, workflowId: String): List<DurableAgentAttempt>`
    (`@Transactional(readOnly=true)`). Map to the runtime-independent
    `agentattempt/domain/DurableAgentAttemptDto` via the provided
    `DurableAgentAttempt.toDto()` extension (bounded, secret-free). DTO fields:
    `attemptId, stepId, attemptNumber, agentName, status(String), caseId, failureCode?,
    resultEvidenceId?, environmentRef?, expectedEnvironmentRevision?, revision, createdAt,
    startedAt?, completedAt?`. Status strings from `AgentAttemptStatus.dbValue`:
    `pending, claiming, starting, running, waiting_human, succeeded, failed, indeterminate, interrupted`.
- **Oracles** — read-only Cypher via injected `Neo4jClient` (there is **no**
  list-by-workstream method on `OracleExecutionRepository`, and oracle files are
  off-limits). `OracleExecution` nodes carry `organizationId, workstreamId, namespaceId,
  workflowId, executionId, oracleId, status, revision, createdAt, updatedAt`.
  Status strings: `running, succeeded, failed, cancelled`. Query failed ones scoped:
  `MATCH (e:OracleExecution) WHERE e.organizationId=$org AND e.workstreamId=$ws AND e.status='failed' RETURN e.workflowId AS workflowId, e.executionId AS executionId, e.oracleId AS oracleId, e.namespaceId AS namespaceId, e.updatedAt AS updatedAt ORDER BY e.updatedAt DESC`.
  (This only reads existing nodes; it writes nothing and modifies no oracle code.)
- **Environments** — `environment/persistence/WorkEnvironmentRepository` bean:
  - `list(scope: TenantScope): List<WorkEnvironment>` (already scoped to org+workstream).
  - `WorkEnvironment` fields: `organizationId, workstreamId, environmentId, workUnitId,
    workflowId, namespaceId, lifecycleState (WorkEnvironmentState), createdAt, revision, envType, ...`.
    `WorkEnvironmentState` dbValues include `provisioning, ready, busy, decommissioned`.
  - Optionally `environment/service/WorkUnitEnvironmentService.inspect(scope, workflowId)` for detail.

### Test harness (verified)
- `factory-service/src/test/kotlin/io/whozoss/factory/Neo4jDomainIntegrationTest.kt`
  — base class; `protected val scope = TenantScope(ORGANIZATION_ID, WORKSTREAM_ID)` with
  `ORGANIZATION_ID = "org-local-dev"`, `WORKSTREAM_ID = "ws-default"`. Graph cleared per test.
- `Neo4jIntegrationTest` — `@SpringBootTest(RANDOM_PORT)`, embedded Neo4j, provides
  `TestRestTemplate` and `neo4jDriver`. Existing HTTP tests use `web/TestJwt.issueJwt(claims, secret)`
  and `factoryProperties.security.fakeIdpSecret` (see
  `artifact/web/ArtifactAdminControllerIntegrationTest.kt` for the exact Bearer-token pattern).
  Authenticated principals resolve (via `LocalDevMembershipResolver`) to
  org `org-local-dev` / workstream `ws-default`.
- Existing `workstream/WorkstreamRepositoryIntegrationTest.kt` must keep passing (evolve it,
  do not delete its asserted keys — adding keys to the view map is safe).

---

## Design decisions (resolve the modelling tensions)

1. **Registry id vs tenant scope.** The registry node is keyed by
   `(organizationId, workstreamId-slug)`; `scope.workstreamId` from the trust context is the
   caller's tenant workstream. For the projection, the trust boundary is enforced as:
   > the `{workstreamId}` path segment MUST equal `caller.scope.workstreamId`.
   If it differs → `factoryError(403, "WORKSTREAM_BOUNDARY_VIOLATION", ...)`. All downstream
   aggregation queries then use **`caller.scope`** (never a scope rebuilt from the path/body).
   This guarantees `workstreamId`/`organizationId` come only from the trusted `TenantScope`.
2. **Read-only.** The projection service and endpoint perform zero writes. Oracle reads go
   through `Neo4jClient` SELECT-only Cypher. No `@Transactional` write methods.
3. **Status enum.** Introduce `WorkstreamStatus { ACTIVE("active"), PAUSED("paused"),
   ARCHIVED("archived") }` with `dbValue`/`fromDbValue`. Persist as string; validate on write.
   Keep `"active"` compatible with existing tests.
4. **Bounds.** Central `object WorkstreamBounds { const val MAX_LIMIT = 50; const val
   DEFAULT_LIMIT = 20; const val MAX_WORKFLOWS_SCANNED = 50 }`. Every incoming `limit` is
   `coerceIn(1, MAX_LIMIT)`; every response list is `.take(limit)`; the number of workflows
   iterated for per-workflow sub-queries is capped at `MAX_WORKFLOWS_SCANNED`, and the response
   reports `truncated: Boolean` + total `count` alongside the bounded `items`.
5. **ETag / revision.** `workstreamRevision` = stable SHA-256 (reuse JDK `MessageDigest`,
   hex, first 16 chars) over a canonical string built from: workstream entity `revision` +
   `updatedAt`, plus the aggregated counts and the max `updatedAt`/timestamp observed across
   sections. Return it both as a DTO field `workstreamRevision` and as the HTTP `ETag` response
   header (quoted). Deterministic for identical state.
6. **allowedWorkflowTypes enforcement.** If the workstream's `allowedWorkflowTypes` is
   non-empty, workflows whose `projection.workflowType` is not in the set are excluded from the
   active-workflow aggregation and counted under `boundaryViolations` (do not throw — it's a
   read). If a `namespaceId` query filter is supplied and the workstream declares a
   `namespaceId`, a mismatch → `factoryError(403, "WORKSTREAM_BOUNDARY_VIOLATION", ...)`.

---

## Files to create / modify (all inside `workstream/`)

### MODIFY — `persistence/WorkstreamNode.kt`
Add nullable/defaulted properties (Spring Data Neo4j maps new props with no migration):
`namespaceId: String? = null`, `controllerAgentRef: String? = null`,
`allowedWorkflowTypes: List<String> = emptyList()`, `governancePolicyRef: String? = null`.
Keep `name` (title alias handled in DTO). Keep `revision, createdAt, updatedAt`.

### NEW — `domain/WorkstreamStatus.kt`
Enum `ACTIVE/PAUSED/ARCHIVED` with `dbValue` + `fromDbValue(value): WorkstreamStatus`
(throws `BadRequestException("...", mapOf("code" to "INVALID_WORKSTREAM_STATUS"))` on unknown).

### NEW — `domain/Workstream.kt`
`data class Workstream(workstreamId, namespaceId?, slug (==workstreamId), title (==name),
status: WorkstreamStatus, controllerAgentRef?, allowedWorkflowTypes: List<String>,
governancePolicyRef?, revision: Int, createdAt: Instant, updatedAt: Instant)` plus mappers
`WorkstreamNode.toDomain()` and a node builder. `slug` and `title` are read-only aliases of
`workstreamId` / `name` to satisfy the required field names without storage duplication.

### MODIFY — `persistence/Neo4jWorkstreamRepository.kt`
- Enrich `create(...)` to accept the new optional fields (overload or a single
  `create(scope, Workstream)` plus keep the old 4-arg `create` delegating to it for the
  existing test). Set `createdAt=updatedAt=now`, `revision=1`.
- Add `save(scope, workstream): Workstream` / `update(...)` that **bumps `revision` by 1**,
  sets `updatedAt=now`, preserves `createdAt`. Fail with `ResourceNotFoundException`
  (`NOT_FOUND`) when the node is absent in scope.
- Add `findDomain(scope, slug): Workstream?` (returns the domain object for the projection).
- Enrich `toView()` to include the new fields (`slug, name, title, status, revision,
  namespaceId, controllerAgentRef, allowedWorkflowTypes, governancePolicyRef, createdAt,
  updatedAt`). Adding keys keeps existing assertions valid.
- Keep every statement scoped by `scope.organizationId` (existing composite-id pattern).

### MODIFY — `persistence/SpringDataNeo4jWorkstreamRepository.kt`
Only if a scoped count/read is needed for revision; otherwise leave untouched.

### MODIFY — `WorkstreamService.kt`
- Add status validation via `WorkstreamStatus.fromDbValue`.
- Enriched `create(scope, request)` accepting the new fields; keep the existing slug/dup
  validation and error codes. Validate `allowedWorkflowTypes` entries are non-blank slugs.
- `update(scope, slug, request, expectedRevision: Int?)`: load → apply changes → save with
  revision bump; `expectedRevision` mismatch → `RevisionConflictException` (`REVISION_CONFLICT`).
- `get(scope, slug): Map<String,Any?>` → `ResourceNotFoundException` when absent.
- Boundary helper `assertWithinWorkstream(caller, pathWorkstreamId)` →
  `factoryError(403, "WORKSTREAM_BOUNDARY_VIOLATION", ...)` when `pathWorkstreamId != caller.scope.workstreamId`.

### NEW — `web/WorkstreamDtos.kt`
- `object WorkstreamBounds { MAX_LIMIT=50; DEFAULT_LIMIT=20; MAX_WORKFLOWS_SCANNED=50 }`.
- Request DTOs: `CreateWorkstreamRequest`, `UpdateWorkstreamRequest` (nullable fields,
  Jackson-friendly). Response DTO `WorkstreamResponse` (enriched registry view).
- Projection DTOs (all bounded):
  ```
  WorkstreamProjectionResponse(
    workstreamId, namespaceId?, status, workstreamRevision,
    activeWorkflows: Section<WorkflowSummary>,
    steps: StepCounts,                      // running, waitingHuman, blocked (+ bounded items)
    attempts: Section<AttemptSummary>,
    humanActions: Section<HumanActionSummary>,
    failedOracles: Section<OracleFailureSummary>,
    environments: Section<EnvironmentSummary>,
    recentChanges: Section<ChangeSummary>,  // last transitions/timestamps, newest first
    boundaryViolations: Int
  )
  Section<T>(count: Int, items: List<T>, truncated: Boolean)   // items capped at limit
  ```
  Keep summaries small (ids, status, timestamps) — never leak secrets or full payloads.

### NEW — `projection/WorkstreamRevision.kt`
Pure function `compute(workstream: Workstream, parts: List<String>): String` → 16-hex SHA-256
over a canonical, order-stable joined string. Unit-testable in isolation.

### NEW — `projection/WorkstreamProjectionService.kt` (`@Service`)
Constructor-inject: `WorkstreamService` (or the repo `findDomain`), `WorkflowService`,
`DurableAgentAttemptService`, `WorkEnvironmentRepository`, `Neo4jClient`
(optionally `WorkUnitEnvironmentService`). Method:
`fun getAggregatedProjection(caller: FactoryCaller, workstreamId: String, namespaceId: String?, limit: Int): WorkstreamProjectionResponse`.
Steps:
1. `assertWithinWorkstream`; load the workstream domain (404 if absent).
2. If `namespaceId` filter + declared `namespaceId` mismatch → boundary violation.
3. `active = workflowService.listProjections(scope, namespaceId, "active")`; cap workflows at
   `MAX_WORKFLOWS_SCANNED`; filter by `allowedWorkflowTypes` (count rejects into `boundaryViolations`).
4. Steps: parse `projection.steps[].status`; count `running` / `waiting_human` / `blocked`;
   keep a bounded sample.
5. Attempts: for each (bounded) workflow `durableAgentAttemptService.findByWorkflow(scope,
   nsId, wfId).map { it.toDto() }`; aggregate count + bounded summary.
6. Human actions: `workflowService.listInteractions(scope, nsId, wfId, openOnly=true)`;
   collect `items`; count + bounded summary.
7. Failed oracles: scoped read-only Cypher via `Neo4jClient` (query above); count + bounded summary.
8. Environments: `workEnvironmentRepository.list(scope)`; group by `lifecycleState`;
   count + bounded summary.
9. Recent changes: merge the newest timestamps across sections (workflow revisions/updatedAt,
   attempt completedAt/createdAt, oracle updatedAt, env createdAt), sort desc, take `limit`.
10. `workstreamRevision = WorkstreamRevision.compute(workstream, <canonical parts incl. counts
    + max timestamp>)`. Return the bounded DTO.
No writes anywhere.

### MODIFY — `WorkstreamController.kt`
- Keep `GET` (list) and `POST` (create), wiring the enriched request DTO.
- Add `PUT /{workstreamId}` (update, optional `If-Match`/`expectedRevision`) and
  `GET /{workstreamId}` (single read) — all `resolveFactoryCaller` first.
- Add `GET /{workstreamId}/projection`:
  - `@RequestParam(required=false) namespaceId`, `@RequestParam(required=false) limit`
    (coerced to bounds), hidden `trustContext`.
  - `val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)`.
  - `val dto = projectionService.getAggregatedProjection(caller, workstreamId, namespaceId, limit)`.
  - Return `ResponseEntity.ok().eTag("\"${dto.workstreamRevision}\"").body(dto)`
    (or wrap in `FactoryDataEnvelope` if you keep list/create consistent — pick one and be
    consistent; existing list/create return raw maps, so raw DTO is fine here).
  - Let `FactoryExceptionHandler` map thrown `FactoryHttpException`/`FactoryException` to the
    standard envelope — do not hand-build error bodies.

---

## Tests to create / modify (all under `workstream/` test package)

### MODIFY — `WorkstreamRepositoryIntegrationTest.kt`
Add cases: enriched create persists & reads back new fields; `update` bumps `revision`
and `updatedAt`, preserves `createdAt`; `expectedRevision` mismatch → `RevisionConflictException`;
invalid status → `BadRequestException`. Keep existing assertions intact.

### NEW — `WorkstreamRevisionTest.kt` (plain unit test, no Spring)
Deterministic hash for identical inputs; changes when any part changes; stable length/hex.

### NEW — `WorkstreamProjectionIntegrationTest.kt` (`extends Neo4jDomainIntegrationTest`)
Seed via the real beans with `scope` (`org-local-dev`/`ws-default`):
- Create the workstream registry entry (slug == `WORKSTREAM_ID` so the boundary check passes).
- Publish 1–2 active workflow projections (`workflowService.publishProjection`/`start` or
  `publishProjection`) with steps in `running`/`waiting_human`/`blocked`.
- Register attempts (`durableAgentAttemptService.register(scope, DurableAgentAttempt(...))`).
- Open a human interaction (`workflowService.openInteraction(...)`).
- Insert a failed `OracleExecution` node — seed **through the public
  `OracleExecutionService`/repository bean** (inject `OracleExecutionRepository.save` then
  `updateStatus(..., FAILED, ...)`), NOT by editing oracle code.
- Provision an environment (`workUnitEnvironmentService.provision(...)` or
  `workEnvironmentRepository.insert`).
Assert: section counts & bounded items are correct; `workstreamRevision` present and stable
across two identical reads; lists never exceed `MAX_LIMIT`; `truncated=true` when seeding past
the cap; `allowedWorkflowTypes` filtering increments `boundaryViolations`; projection performed
no mutation (re-read underlying workflow/attempt revisions unchanged).

### NEW — `WorkstreamControllerIntegrationTest.kt` (`extends Neo4jIntegrationTest`)
Use `TestRestTemplate` + Bearer `TestJwt.issueJwt(...)` (mirror
`ArtifactAdminControllerIntegrationTest`):
- `POST` create enriched workstream → 201, enriched body.
- `PUT` update → revision bumped; stale `expectedRevision` → 409 `REVISION_CONFLICT`
  with the standard error envelope.
- `GET /{id}/projection` → 200, body shape, and **`ETag` header equals the body
  `workstreamRevision`** (quoted).
- Boundary: a path `workstreamId` ≠ trusted scope workstream → 403
  `WORKSTREAM_BOUNDARY_VIOLATION` with `{ "error": { "code": ... } }`.
- `limit` above `MAX_LIMIT` is capped (assert returned items ≤ 50).
- Unauthenticated/anonymous → 401 `TRUST_CONTEXT_UNAVAILABLE`.

---

## Verification (factory runs the suite; builder may run locally to debug)

- Primary: `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
  (covers the Gradle `factory-service` test task for the changed module).
- Targeted Gradle debug (from repo root): `./agentos/gradlew -p agentos :factory-service:test
  --tests "io.whozoss.factory.workstream.*"` (adjust to the actual Gradle project path for
  factory-service; check `agentos/settings.gradle.kts`). If run via Nx:
  `pnpm nx test factory-service` (confirm the Nx project name).
- Quality gates the factory may run:
  `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"` and
  `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`.
- Acceptance: all tests under
  `factory-service/src/test/kotlin/io/whozoss/factory/workstream/` pass; enriched CRUD,
  projection calculation, revision/ETag, pagination bounds, and boundary enforcement are
  all asserted. Modified-file claims must list exactly the files touched under the two
  `.../workstream/` directories.

## Out of scope / do not do
- No edits outside `workstream/` (no oracle/workflow/attempt/environment/web/error/migration changes).
- No new write paths into other aggregates; projection is strictly read-only.
- No scratch files written inside the repo tree (use `/tmp`).
- Do not touch migrations or the release pipeline.
