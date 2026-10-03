# Phase 9 — Workstream Agent Controller Case Lifecycle

## Goal

Give the Workstream Agent a stable conceptual identity (its `controllerAgentRef`) that is **decoupled from any single eternal case**. A workstream has at most one *active* controller case at a time; starting fresh or compacting archives the current case and binds a new one, while the `controllerAgentRef` and the workstream identity are preserved. On resumption (new case or compaction) a **bounded resumption context package** is reconstructed from the Phase 5 `WorkstreamProjectionService` — never raw conversation history.

This is a pure Kotlin/Spring Boot feature inside the `factory-service` workstream subsystem.

## STRICT BOUNDARY (do not cross)

- Modify **ONLY**:
  - `factory-service/src/main/kotlin/io/whozoss/factory/workstream/`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workstream/`
- Do **NOT** touch `factory-service/.../agentattempt/`, `agentos/tools/`, or `planchange`.
- **No** database migrations, **no** release scripts.
- Neo4j is schemaless; new node types with defaulted properties load with zero migration (follow the existing `WorkstreamNode` precedent — see its KDoc "New properties are defaulted so pre-existing nodes load without any migration").

## Context: what already exists (Phase 5)

Directory `factory-service/src/main/kotlin/io/whozoss/factory/workstream/`:
- `domain/Workstream.kt` — domain record; **already has `controllerAgentRef: String?`**, plus `slug`/`title` aliases and `toDomain()`/`toNode()` mappers.
- `domain/WorkstreamStatus.kt` — enum `ACTIVE/PAUSED/ARCHIVED` with `fromDbValue` (lowercase wire vocabulary, throws `BadRequestException` on unknown).
- `persistence/WorkstreamNode.kt` — `@Node("Workstream")`, composite id `"$organizationId|$workstreamId"` via `WorkstreamNode.compositeId(...)`. Already stores `controllerAgentRef`.
- `persistence/Neo4jWorkstreamRepository.kt` — `@Repository @Primary`, tenant-scoped; `findDomain(scope, slug)`, `create`, `save` (bumps revision), `toView()` map shape.
- `persistence/SpringDataNeo4jWorkstreamRepository.kt` — `Neo4jRepository<WorkstreamNode, String>` + `findAllByOrganization`.
- `projection/WorkstreamProjectionService.kt` — `@Service`; `getAggregatedProjection(caller, workstreamId, namespaceId, limit): WorkstreamProjectionResponse`. Composes a bounded, read-only view (active workflows, steps of interest running/waiting_human/blocked, attempts, open human actions, failed oracles, environments, recent changes) and a stable 16-hex `workstreamRevision` ETag. **This is the single source for the resumption package.**
- `projection/WorkstreamRevision.kt` — `object WorkstreamRevision.compute(workstream, parts): String` — pure deterministic 16-hex SHA-256 prefix.
- `web/WorkstreamDtos.kt` — `WorkstreamBounds` (MAX_LIMIT=50, DEFAULT_LIMIT=20, `boundedLimit`), request DTOs, `WorkstreamSection<T>`, summaries, `WorkstreamProjectionResponse`.
- `WorkstreamService.kt` — `@Service`; `findDomain`, `create`, `update`, and **`assertWithinWorkstream(caller, pathWorkstreamId)`** (trust-boundary: path id must equal `caller.scope.workstreamId`, else 403 `WORKSTREAM_BOUNDARY_VIOLATION`).
- `WorkstreamController.kt` — `@RestController @RequestMapping("/api/factory/workstreams")`; endpoints use `resolveFactoryCaller(trustContext, tenantScopeProvider)` then `service.assertWithinWorkstream(caller, workstreamId)`.

Shared infra (read-only reference, DO NOT modify):
- `web/FactoryHttp.kt` — `FactoryCaller(scope, actorId, authorityId, externalUserId)`, `resolveFactoryCaller`, `requireNamespaceQuery`, `factoryError(status, code, message, details)`, `FactoryHttpException`.
- `error/FactoryException.kt` — `BadRequestException`, `ResourceNotFoundException`, `ConflictException`, `RevisionConflictException`, `UnprocessableEntityException`.
- `persistence/TenantScope` (`organizationId`, `workstreamId`), `persistence/TenantScopeProvider`.
- `persistence/ScopedRepository<V, ID>` interface (optional to implement; the controller-case repo may be a plain `@Repository`).

Reference implementation pattern to copy (durable-execution, in `agentattempt/` — read-only reference, DO NOT modify): `DurableAgentAttemptNode` (composite id, `fromDomain`/`toDomain`), `SpringDataNeo4jDurableAgentAttemptRepository.register` (idempotent `MERGE ... ON CREATE SET`, CAS statements returning `count(a)`), `Neo4jDurableAgentAttemptRepository` (`@Transactional`, read-back-to-disambiguate on CAS miss). The resumption-context bounding precedent is `DurableAgentAttempt.resumptionContext` + `StepQuestionLimits.RESUMPTION_CONTEXT_BYTES` (compact-or-reject). We mirror the *shape* of these patterns but keep everything inside `workstream/`.

## Design

### Domain model

A **controller case execution** is an immutable historical record of one controller case bound to a workstream over its lifetime. At most one is `active` per workstream; the rest are `archived`.

New files under `domain/`:

1. `domain/ControllerCaseStatus.kt`
   - `enum class ControllerCaseStatus(val dbValue: String) { ACTIVE("active"), ARCHIVED("archived") }`
   - `companion.fromDbValue(value: String)` — case-insensitive, throws `BadRequestException(code = "INVALID_CONTROLLER_CASE_STATUS")` on unknown (mirror `WorkstreamStatus`).

2. `domain/ControllerCaseExecution.kt`
   - `data class ControllerCaseExecution(`
     - `organizationId: String,`
     - `workstreamId: String,`
     - `caseId: String,` — the controller case identifier
     - `controllerAgentRef: String,` — the stable agent identity preserved across cases
     - `status: ControllerCaseStatus = ControllerCaseStatus.ACTIVE,`
     - `sequence: Int,` — 1-based ordinal of this case within the workstream (1st, 2nd, …)
     - `startedAt: Instant,`
     - `archivedAt: Instant? = null,`
     - `compactionReason: String? = null,` — why the predecessor was compacted (null for the first case)
     - `contextSummary: String? = null,` — the bounded resumption summary captured at start (JSON string, bounded)
     - `contextRevision: String? = null,` — the `workstreamRevision` ETag the context package was built from (provenance/metadata)
     - `createdAt: Instant = Instant.now(),`
     - `updatedAt: Instant = Instant.now(),`
   - `)`
   - Add `ControllerCaseExecutionNode.toDomain()` and `ControllerCaseExecution.toNode()` extension mappers (mirror `Workstream.kt`).

3. `domain/ControllerCaseBounds.kt` (small `object`)
   - `const val MAX_CONTEXT_SUMMARY_BYTES = 8192` (UTF-8) — strict upper bound of the serialized resumption package stored on the case.
   - `const val MAX_WORKFLOW_ITEMS = 10`, `MAX_HUMAN_ACTIONS = 10`, `MAX_BLOCKERS = 10`, `MAX_RECENT_CHANGES = 10` — per-section caps for the package.
   - `const val MAX_COMPACTION_REASON_CHARS = 500`.

### Persistence

New files under `persistence/`:

4. `persistence/ControllerCaseExecutionNode.kt`
   - `@Node("ControllerCaseExecution")` data class.
   - `@Id val id: String` composite key `"$organizationId|$workstreamId|$caseId"` via `companion.compositeId(organizationId, workstreamId, caseId)`.
   - Store all domain fields; `status` as `dbValue` string. All new properties defaulted.
   - `companion.fromDomain(scope, execution)` and instance `toDomain()`.

5. `persistence/SpringDataNeo4jControllerCaseRepository.kt`
   - `interface SpringDataNeo4jControllerCaseRepository : Neo4jRepository<ControllerCaseExecutionNode, String>`
   - `@Query` methods:
     - `findActiveByWorkstream(organizationId, workstreamId): ControllerCaseExecutionNode?` — `MATCH (c:ControllerCaseExecution) WHERE c.organizationId=$org AND c.workstreamId=$ws AND c.status='active' RETURN c` (invariant: at most one).
     - `findAllByWorkstream(organizationId, workstreamId): List<ControllerCaseExecutionNode>` — ordered `ORDER BY c.sequence ASC`.
     - `maxSequence(organizationId, workstreamId): Int?` — `RETURN max(c.sequence)` (null when none).
     - `archiveActive(organizationId, workstreamId, archivedAt, compactionReason): Int` — CAS: `MATCH ... WHERE c.status='active' SET c.status='archived', c.archivedAt=$archivedAt, c.compactionReason=coalesce(c.compactionReason,$compactionReason), c.updatedAt=$archivedAt RETURN count(c)`. (compactionReason here records why THIS case was archived; see note below.)

   > **compactionReason semantics**: `compactionReason` on a case describes why *that* case ended / why the *next* case was created. Store the operator-supplied reason on the newly archived case (so history reads "case N archived because X" and "case N+1 context rebuilt from projection revision R"). Keep it simple and documented in KDoc.

6. `persistence/Neo4jControllerCaseRepository.kt`
   - `@Repository` (no `@Primary` needed — single impl). Tenant-scoped like `Neo4jWorkstreamRepository`.
   - Methods (all take `scope: TenantScope`, derive org from scope — never from input):
     - `findActive(scope, workstreamId): ControllerCaseExecution?`
     - `listHistory(scope, workstreamId): List<ControllerCaseExecution>` (ordered by sequence)
     - `startFirst(scope, execution): ControllerCaseExecution` — idempotent `MERGE` by composite id (ON CREATE SET all fields, sequence from `maxSequence+1 ?: 1`). Use the `register`-style `MERGE ... ON CREATE SET` so a re-start with the same `caseId` is idempotent.
     - `archiveAndStart(scope, workstreamId, newCaseId, controllerAgentRef, compactionReason, contextSummary, contextRevision, now): ControllerCaseExecution` — inside `@Transactional`: (a) `archiveActive(...)` the current active case stamping `compactionReason`; (b) compute `sequence = maxSequence+1`; (c) `MERGE` the new active case node. Returns the new active execution.
   - Keep the `@Transactional` import from Spring; follow the `Neo4jDurableAgentAttemptRepository` transactional-CAS style but no lease/owner fencing is needed here (single-writer control-plane operation; optimistic by workstream `revision` is handled at the service layer — see below).

### Resumption context package (bounded)

New file under `projection/`:

7. `projection/ControllerResumptionPackageBuilder.kt` (a `@Component` or `object` with a build function; prefer `@Component` so it can depend on nothing but takes the projection as input).
   - Function: `build(projection: WorkstreamProjectionResponse): ControllerResumptionPackage`
   - Maps the **already-bounded** Phase 5 projection into a compact package:
     - `activeWorkflows`: take `MAX_WORKFLOW_ITEMS` of `projection.activeWorkflows.items` → `{ workflowId, workflowType, title, status }`.
     - `openHumanInteractions`: take `MAX_HUMAN_ACTIONS` of `projection.humanActions.items` → `{ interactionId, workflowId, interactionType, status }`.
     - `blockers`: derive from `projection.steps.items.filter { it.status == "blocked" }` (cap `MAX_BLOCKERS`) plus `projection.failedOracles.items` → `{ kind, refId, workflowId }`.
     - `recentChanges`: take `MAX_RECENT_CHANGES` of `projection.recentChanges.items`.
     - `counts`: `{ activeWorkflows, running, waitingHuman, blocked, attempts, humanActions, failedOracles, environments }` from the projection section counts.
     - `sourceRevision`: `projection.workstreamRevision`.
   - **Never** inject raw conversation histories — only these summarized sections. This is a hard requirement.
   - Serialization to the stored `contextSummary` string: serialize the package to compact JSON (use the existing Jackson `ObjectMapper` bean, injected). If the serialized bytes exceed `ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES`, progressively drop the lowest-priority sections (recentChanges → blockers → openHumanInteractions, keeping `counts` and `sourceRevision` always) and re-serialize; this is the "compact-or-reject" precedent from `AgentStepQuestionService.compact`. Document the bound in KDoc.

### Service

New file:

8. `ControllerCaseService.kt` (`@Service`)
   - Dependencies: `WorkstreamService`, `Neo4jControllerCaseRepository`, `WorkstreamProjectionService`, `ControllerResumptionPackageBuilder`, `com.fasterxml.jackson.databind.ObjectMapper`.
   - Methods (every one calls `workstreamService.assertWithinWorkstream(caller, workstreamId)` first, and loads the workstream via `workstreamService.findDomain(scope, workstreamId)` → 404 `WORKSTREAM_NOT_FOUND` when absent):
     - `getActiveCase(caller, workstreamId): ControllerCaseExecution?` — current active case (or null if none started yet).
     - `getContextPackage(caller, workstreamId, namespaceId, limit): ControllerResumptionPackage` — builds the projection via `projectionService.getAggregatedProjection(...)` and returns the bounded package (does NOT persist). Used by the Cockpit "preview resumption context" call.
     - `startControllerCase(caller, workstreamId, request): ControllerCaseExecution` — start the FIRST controller case:
       - Requires the workstream to declare a `controllerAgentRef` (from the registry entry; else 422 `CONTROLLER_AGENT_REF_REQUIRED`). Optionally the request may carry a `caseId` (generate a UUID if absent).
       - If an active case already exists → 409 `CONTROLLER_CASE_ALREADY_ACTIVE` (compaction is the explicit path to renew).
       - Build + serialize the bounded context package from the current projection, persist via `repository.startFirst(...)` with `sequence=1`, `status=active`, `startedAt=now`, `contextRevision=package.sourceRevision`.
     - `compactControllerCase(caller, workstreamId, request): ControllerCaseExecution` — explicit compaction / renew:
       - Requires an existing active case (else 409 `NO_ACTIVE_CONTROLLER_CASE`).
       - Preserves `controllerAgentRef` (reads it from the active case; it never changes) and workstream identity.
       - Validate `request.compactionReason` length ≤ `MAX_COMPACTION_REASON_CHARS` (optional field).
       - Rebuild bounded context package from the fresh projection, then `repository.archiveAndStart(...)`: archive the current active case stamping `compactionReason`, start a new active case (`sequence = previous+1`, new `caseId` = request.caseId ?: UUID, `contextSummary` = serialized package, `contextRevision` = package.sourceRevision).
       - Returns the new active case.
     - `listHistory(caller, workstreamId): List<ControllerCaseExecution>` — active + archived, ordered by sequence (for Cockpit history view).
   - Keep all identity (`organizationId`, `workstreamId`) from `caller.scope`, never from request body.
   - `caseId` generation: `java.util.UUID.randomUUID().toString()` when the request omits one.

### DTOs

New file `web/ControllerCaseDtos.kt` (keep DTOs out of `WorkstreamDtos.kt` to respect single-responsibility and avoid churn there):
- `data class StartControllerCaseRequest(val caseId: String? = null)`
- `data class CompactControllerCaseRequest(val caseId: String? = null, val compactionReason: String? = null)`
- `data class ControllerCaseResponse(` — view of a `ControllerCaseExecution`: `workstreamId, caseId, controllerAgentRef, status, sequence, startedAt, archivedAt, compactionReason, contextRevision` (do **not** echo the full `contextSummary` JSON blob here — expose it only through the dedicated context endpoint). Add a mapper `ControllerCaseExecution.toResponse()`.
- `data class ControllerCaseHistoryResponse(val workstreamId: String, val activeCaseId: String?, val cases: List<ControllerCaseResponse>)`
- `data class ControllerResumptionPackage(` with nested compact summary types:
  - `val workstreamId: String, val sourceRevision: String,`
  - `val counts: ControllerContextCounts,`
  - `val activeWorkflows: List<ContextWorkflow>,`
  - `val openHumanInteractions: List<ContextHumanAction>,`
  - `val blockers: List<ContextBlocker>,`
  - `val recentChanges: List<ContextChange>`
  - plus the nested data classes `ControllerContextCounts`, `ContextWorkflow`, `ContextHumanAction`, `ContextBlocker`, `ContextChange` (compact, secret-free, mirrors the projection summary shapes).

### REST endpoints (extend `WorkstreamController.kt`)

Add to the existing controller (inject `ControllerCaseService` as a new constructor param — this is inside the allowed boundary):

- `GET  /api/factory/workstreams/{workstreamId}/controller-case`
  → active case metadata. Returns 200 with `ControllerCaseResponse`, or 404 `NO_ACTIVE_CONTROLLER_CASE` when none active (document this choice; alternatively 200 with a `null`-ish body — **choose 404 for "no active case" to keep the contract explicit** and assert it in tests).
- `GET  /api/factory/workstreams/{workstreamId}/controller-case/history`
  → `ControllerCaseHistoryResponse` (active + archived, ordered by sequence).
- `GET  /api/factory/workstreams/{workstreamId}/controller-case/context`
  → `ControllerResumptionPackage` built from the current projection (accepts `namespaceId` and `limit` query params, coerced by `WorkstreamBounds.boundedLimit`). Preview, no mutation.
- `POST /api/factory/workstreams/{workstreamId}/controller-case`
  → start the first controller case; body `StartControllerCaseRequest?`. Returns 201 with `ControllerCaseResponse`.
- `POST /api/factory/workstreams/{workstreamId}/controller-case/compact`
  → explicit compaction / renew; body `CompactControllerCaseRequest?`. Returns 200 with the new active `ControllerCaseResponse`.

Every handler: `val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)` then delegate to `ControllerCaseService` (which enforces `assertWithinWorkstream`). Mirror the existing `@Operation` / `@Parameter(hidden = true) trustContext: TrustContext?` style. Add `@PathVariable workstreamId`.

### Resiliency / unavailability policy (documentation requirement)

Add a clear KDoc block on `ControllerCaseService` (and reference it in the plan-level docstring) stating:

> The controller case governs only the *conversational* interlocution with the Workstream Agent. If the Workstream Agent or its controller case is unavailable (no active case, agent offline, or mid-compaction), the underlying workflows, durable attempts, oracle executions and environments continue to progress according to their own policies — this service performs ZERO writes to those aggregates and holds no lease over them. Only new conversational interactions wait for a controller case to be (re)bound.

This is a documentation/modelling requirement — there is no runtime coupling to add; the proof is that `ControllerCaseService` depends only on the workstream registry, the controller-case repository and the read-only projection, and never mutates workflow/attempt/oracle/environment state. A test asserts the read-only invariant (see below).

## Files to create / modify (summary)

Create:
- `workstream/domain/ControllerCaseStatus.kt`
- `workstream/domain/ControllerCaseExecution.kt`
- `workstream/domain/ControllerCaseBounds.kt`
- `workstream/persistence/ControllerCaseExecutionNode.kt`
- `workstream/persistence/SpringDataNeo4jControllerCaseRepository.kt`
- `workstream/persistence/Neo4jControllerCaseRepository.kt`
- `workstream/projection/ControllerResumptionPackageBuilder.kt`
- `workstream/ControllerCaseService.kt`
- `workstream/web/ControllerCaseDtos.kt`

Modify:
- `workstream/WorkstreamController.kt` — inject `ControllerCaseService`, add the 5 endpoints.

Tests (create under `src/test/kotlin/io/whozoss/factory/workstream/`):
- `ControllerCaseServiceIntegrationTest.kt` (extends `Neo4jDomainIntegrationTest`)
- `ControllerCaseControllerIntegrationTest.kt` (extends `Neo4jIntegrationTest`)

## Coding conventions (match the existing files exactly)

- Package `io.whozoss.factory.workstream[.*]`. No semicolons, single quotes N/A (Kotlin), 120-char lines.
- Explicit return types on public functions. `data class` for domain/DTOs. PascalCase types, camelCase members, kebab-case is for URL paths only.
- Error throwing via the `error/` exception classes with `mapOf("code" to "...")` detail maps (follow `WorkstreamService`), or `factoryError(status, code, message, details)` for ad-hoc HTTP failures.
- Cypher `@Query` strings use the `${'$'}param` escaping idiom (see `SpringDataNeo4jWorkstreamRepository`).
- Tenant identity always from `scope` / `caller.scope`, never from request bodies/paths beyond the `assertWithinWorkstream` guard.
- Keep every returned collection bounded; document the bounds in KDoc.

## Tests to write

### `ControllerCaseServiceIntegrationTest` (domain-level, autowire `ControllerCaseService`, `WorkstreamService`, and the aggregate services used to seed state)

Base: `Neo4jDomainIntegrationTest` (provides `scope`, `ORGANIZATION_ID`, `WORKSTREAM_ID = "ws-default"`, graph cleared per test). Build a `caller` the same way `WorkstreamProjectionIntegrationTest` does: `FactoryCaller(scope, "controller-tester", "controller-tester", "controller-tester")`.

1. `starting a controller case requires a controllerAgentRef and binds it as active`
   - Create a registry entry WITHOUT `controllerAgentRef` → `startControllerCase` throws (422 `CONTROLLER_AGENT_REF_REQUIRED`, `UnprocessableEntityException`).
   - Create with `controllerAgentRef = "agent://controller"` → start succeeds, `status == ACTIVE`, `sequence == 1`, `controllerAgentRef` preserved, `getActiveCase` returns it.
2. `starting twice without compaction is a conflict`
   - After a successful start, a second `startControllerCase` → `ConflictException` (`CONTROLLER_CASE_ALREADY_ACTIVE`).
3. `compaction archives the current case and starts a new one with the same agent identity`
   - Start case 1, seed some projection state (publish a workflow via `WorkflowService` like the projection test does), then `compactControllerCase(reason = "token budget")`.
   - Assert: previous case now `ARCHIVED` with `archivedAt != null` and `compactionReason == "token budget"`; new active case `sequence == 2`, same `controllerAgentRef`, same `workstreamId`, different `caseId`; `getActiveCase` returns the new one; `listHistory` returns both ordered by sequence (1 archived, 2 active).
4. `compaction without an active case is rejected`
   - `compactControllerCase` on a workstream with no active case → `ConflictException` (`NO_ACTIVE_CONTROLLER_CASE`).
5. `the resumption context package is bounded and built from the projection, never a raw dump`
   - Seed workflows, attempts, an open human interaction, a failed oracle, an environment (reuse the seeding helpers from `WorkstreamProjectionIntegrationTest`).
   - `getContextPackage(...)`: assert `sourceRevision` matches the projection's `workstreamRevision` (16 hex), each section size ≤ its `ControllerCaseBounds` cap, counts match the projection section counts, and that the package contains NO field holding conversation text (assert the serialized JSON size ≤ `MAX_CONTEXT_SUMMARY_BYTES` and that only the expected keys are present).
   - After `startControllerCase`, assert the persisted case `contextRevision` equals that `sourceRevision` and `contextSummary` is non-null and within the byte bound.
6. `the service never mutates the underlying aggregates` (read-only invariant)
   - Seed workflow + attempt, snapshot their revisions (as `WorkstreamProjectionIntegrationTest` does), call `getContextPackage` + `startControllerCase` + `compactControllerCase`, re-read revisions → unchanged. This proves the resiliency/decoupling policy.
7. `operations are tenant/workstream-boundary enforced`
   - `getActiveCase`/`startControllerCase`/`compactControllerCase` with a `workstreamId != caller.scope.workstreamId` → `FactoryHttpException` with `WORKSTREAM_BOUNDARY_VIOLATION`, status 403.
   - Operation on an unknown (not-created) workstream → `ResourceNotFoundException` (`WORKSTREAM_NOT_FOUND`).
8. (repository-level, optional but recommended) `starting the same caseId twice is idempotent` — call `repository.startFirst` twice with the same `caseId`; one node, no duplicate.

### `ControllerCaseControllerIntegrationTest` (HTTP boundary, extends `Neo4jIntegrationTest`)

Mirror `WorkstreamControllerIntegrationTest`'s harness (`TestRestTemplate`, `TestJwt.issueJwt`, `exchange` helper, `errorCode` helper, `WORKSTREAM_ID = "ws-default"`).

1. `GET controller-case returns 404 when no case is active`
   - Create workstream (with `controllerAgentRef`), GET `/controller-case` → 404, error code `NO_ACTIVE_CONTROLLER_CASE`.
2. `POST controller-case starts the first case and GET returns it`
   - POST → 201 with `status=active`, `sequence=1`, `controllerAgentRef` echoed; GET `/controller-case` → 200 same `caseId`.
3. `POST controller-case/compact archives and renews preserving the agent identity`
   - After start, POST `/controller-case/compact` with `{ "compactionReason": "manual" }` → 200, new `caseId`, `sequence=2`, same `controllerAgentRef`.
   - GET `/controller-case/history` → active + archived, archived carries `compactionReason=manual`.
4. `GET controller-case/context returns a bounded resumption package`
   - → 200, body has `sourceRevision` matching `^[0-9a-f]{16}$`, `counts` present, section arrays present and bounded; a repeat read with identical state yields the same `sourceRevision`.
5. `starting a controller case without a controllerAgentRef is a 422`
   - Create workstream without `controllerAgentRef`, POST `/controller-case` → 422, code `CONTROLLER_AGENT_REF_REQUIRED`.
6. `a workstream outside the trusted scope is a 403 WORKSTREAM_BOUNDARY_VIOLATION`
   - GET/POST on `/api/factory/workstreams/ws-untrusted/controller-case...` → 403 `WORKSTREAM_BOUNDARY_VIOLATION` (mirror the existing boundary test).

## Verification

Factory runs the test suite automatically. For local debugging:

- Targeted tests:
  - `pnpm nx test factory-service` (whole module), or module-scoped Gradle test if faster.
- Quality gates the factory can run:
  - `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
  - `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`

Acceptance is met when:
- The 9 production files compile and wire into Spring (new `@Service`/`@Repository`/`@Component`/`@Node` beans).
- Both new integration test classes pass, covering: controllerAgentRef binding + start; compaction/renew (archive previous, start new, same agent/workstream identity); bounded context package built from the projection (no full conversation dump); the 5 Cockpit endpoints; and the read-only / resiliency invariant.
- No file outside `factory-service/.../workstream/` (main + test) is modified; no migrations or release scripts touched.

## Notes / pitfalls

- `WorkstreamProjectionResponse` section counts are the authoritative source for the package `counts`; the `*.items` lists are already bounded by `WorkstreamBounds`, so re-capping to the smaller `ControllerCaseBounds` caps is cheap and safe.
- Neo4j: the active-case uniqueness invariant is enforced by convention (only `archiveAndStart`/`startFirst` write cases, both inside `@Transactional`). Do NOT attempt to add a DB uniqueness constraint (that would be a migration). A `findActiveByWorkstream` returning a single node is sufficient; if two ever appeared, pick the highest `sequence` and document it — but the write paths prevent it.
- Keep `contextSummary` as an opaque JSON string column on the node; only the dedicated `/context` endpoint re-derives the live package (do not store-and-serve stale context from the node in the `ControllerCaseResponse`).
- Use the injected Jackson `ObjectMapper` bean for serialization; do not instantiate a new one.
- `UnprocessableEntityException` and `ConflictException` map to 422/409 via `FactoryExceptionHandler`; confirm their constructor signatures match the `(message, details)` shape used in `WorkstreamService` before use.
