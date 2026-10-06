# Phase 5 — Workstream Versioned Registry & Bounded Aggregated Projection

## What changed

The `workstream` aggregate in `factory-service` was upgraded from a flat
`{slug, name, status, revision}` map into a **versioned registry** with a
**read-only aggregated projection endpoint**. All code lives strictly inside
`factory-service/src/main/kotlin/io/whozoss/factory/workstream/` (and its test
package); other aggregates (`agentattempt`, `workflow`, `oracle`, `environment`)
are consumed read-only through their existing public beans and are unmodified.

### 1. Versioned workstream registry

- `persistence/WorkstreamNode.kt` gains the registry fields: `namespaceId`,
  `controllerAgentRef`, `allowedWorkflowTypes` (empty = unrestricted),
  `governancePolicyRef`, plus the existing `revision`, `createdAt`, `updatedAt`.
  New properties are defaulted, so pre-existing nodes load with no migration.
- New `domain/Workstream.kt` — domain model with `slug`/`title` as read-only
  aliases of `workstreamId`/`name`, plus `toDomain()`/`toNode()` mappers.
- New `domain/WorkstreamStatus.kt` — enum (`active` | `paused` | `archived`)
  with case-insensitive `fromDbValue`, rejecting unknown statuses with
  `INVALID_WORKSTREAM_STATUS` (400).
- `persistence/Neo4jWorkstreamRepository.kt` gains `findDomain`, a
  domain-based `create(scope, Workstream)` (revision 1, `createdAt == updatedAt`),
  and `save(scope, Workstream)` which bumps `revision` by one, refreshes
  `updatedAt`, preserves `createdAt`, and 404s on an absent slug. The map view
  (`toView`) is enriched with all registry fields while keeping the old keys.
- `WorkstreamService` gains `get`, `findDomain`, an enriched
  `create(scope, CreateWorkstreamRequest)` (old scalar-arg `create` kept as a
  delegating overload), `update(...)` with optimistic locking via
  `expectedRevision` (stale → `RevisionConflictException` /
  `REVISION_CONFLICT`, 409), `assertWithinWorkstream(caller, pathId)` which
  403s with `WORKSTREAM_BOUNDARY_VIOLATION` when the path id differs from the
  caller's trusted `scope.workstreamId`, and `allowedWorkflowTypes` validation
  (slug shape, deduped, capped at `WorkstreamBounds.MAX_LIMIT`).
- `WorkstreamController` now exposes:
  - `GET /api/factory/workstreams/{workstreamId}` — read one entry (boundary-checked).
  - `PUT /api/factory/workstreams/{workstreamId}` — save a new revision; the
    optimistic-lock precondition comes from the body's `expectedRevision` or,
    equivalently, the `If-Match` header (`"3"`, `3`, weak `W/` prefixes parsed).
  - `POST` now binds a typed `CreateWorkstreamRequest` instead of a raw map.
  - All errors follow the standard envelope `{ "error": { "code", "message" } }`.

### 2. Read-only aggregated projection

New `projection/WorkstreamProjectionService.kt` backs
`GET /api/factory/workstreams/{workstreamId}/projection` (with optional
`namespaceId` and `limit` query params). It **calculates on demand, writes
nothing, duplicates no state**, composing:

- **Active workflows** — from `WorkflowService.listProjections(scope, ns, "active")`;
  filtered against the workstream's declared `allowedWorkflowTypes`, with
  out-of-declaration entries counted in `boundaryViolations`. Scan capped at
  `MAX_WORKFLOWS_SCANNED` (50).
- **Steps of interest** — counts of `running` / `waiting_human` / `blocked`
  steps across the scanned workflows, with bounded summaries.
- **Attempts** — via `DurableAgentAttemptService.findByWorkflow` mapped
  through the runtime-independent `DurableAgentAttempt.toDto()`.
- **Human actions** — open interactions via `WorkflowService.listInteractions(..., openOnly = true)`.
- **Failed oracles** — read-only Cypher over `OracleExecution` nodes via the
  injected `Neo4jClient`, scoped by org + workstream, `status='failed'`,
  ordered by `updatedAt DESC`, `LIMIT` bound to the caller's (coerced) limit.
- **Environments** — `WorkEnvironmentRepository.list(scope)` with a
  `byState` lifecycle breakdown.
- **Recent changes** — observed timestamps (workstream update, attempts,
  oracles, environments) merged newest-first, bounded.

Every collection section is a `WorkstreamSection<T>` (`count` + capped `items`
+ `truncated` flag). Bounds live in `web/WorkstreamDtos.kt`'s
`WorkstreamBounds`: `MAX_LIMIT = 50`, `DEFAULT_LIMIT = 20`; any caller limit is
coerced into `[1, 50]`.

### 3. Stable revision / ETag

New `projection/WorkstreamRevision.kt` is a pure function: a 16-hex-char
SHA-256 prefix over a canonical string of the registry entry (org, id,
namespace, status, revision, timestamps) plus aggregated state parts (all
counts, boundary violations, latest observed timestamp). Identical state →
identical revision; any change → different revision. The controller returns it
both as `workstreamRevision` in the body and as the HTTP `ETag` header
(quoted).

### 4. Trust boundary

`workstreamId` and `organizationId` always come from the resolved
`FactoryCaller`/`TenantScope` (never from request input): a path id that
differs from the trusted scope is a 403 `WORKSTREAM_BOUNDARY_VIOLATION`, and a
`namespaceId` filter that contradicts the workstream's declared namespace is
rejected the same way.

## Files

Main (all under `factory-service/src/main/kotlin/io/whozoss/factory/workstream/`):
- `WorkstreamController.kt`, `WorkstreamService.kt` (evolved)
- `domain/Workstream.kt`, `domain/WorkstreamStatus.kt` (new)
- `persistence/WorkstreamNode.kt`, `persistence/Neo4jWorkstreamRepository.kt` (evolved)
- `projection/WorkstreamProjectionService.kt`, `projection/WorkstreamRevision.kt` (new)
- `web/WorkstreamDtos.kt` (new — request/response DTOs + `WorkstreamBounds`)

Tests (all under `factory-service/src/test/kotlin/io/whozoss/factory/workstream/`):
- `WorkstreamRevisionTest.kt` — unit tests of the pure ETag function.
- `WorkstreamRepositoryIntegrationTest.kt` — extended: enriched create,
  revision bump on save/update, `REVISION_CONFLICT`, status/type validation.
- `WorkstreamProjectionIntegrationTest.kt` — seeds live state through the
  owning aggregates' public beans and asserts every section, ETag stability and
  sensitivity, boundary enforcement, limit coercion/truncation, and the
  read-only invariant (attempt/workflow revisions unchanged after projection).
- `WorkstreamControllerIntegrationTest.kt` — HTTP boundary tests: enriched CRUD,
  `If-Match` handling, ETag header coherence, 404/403 codes, out-of-range limit.

Spec: `specs/f3c4bd0f_workstream_aggregate_projection.md` — the full Phase 5
design (boundary rules, consumable beans, design decisions).

## How to use / verify

- Create: `POST /api/factory/workstreams` with `{slug, name|title, status,
  namespaceId?, controllerAgentRef?, allowedWorkflowTypes?, governancePolicyRef?}` → 201.
- Read: `GET /api/factory/workstreams/{slug}` → registry view with `revision`.
- Update: `PUT /api/factory/workstreams/{slug}` with changed fields plus
  `expectedRevision` (or an `If-Match: "<rev>"` header) → 200 with bumped
  revision; stale revision → 409 `REVISION_CONFLICT`.
- Projection: `GET /api/factory/workstreams/{slug}/projection?namespaceId=…&limit=…`
  → `WorkstreamProjectionResponse` + `ETag` header; repeat reads of unchanged
  state return the identical `workstreamRevision`.
- Run the tests:
  `pnpm nx test factory-service` (or target the `workstream` test package).
