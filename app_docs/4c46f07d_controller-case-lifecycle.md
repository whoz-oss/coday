# Phase 9 — Workstream Agent Controller Case Lifecycle

## What changed and why

The Workstream Agent is a single stable conversational interlocutor per workstream, but it must not
require an eternal case. This change adds the full controller case lifecycle inside the
`factory-service` workstream subsystem (`io.whozoss.factory.workstream`): a workstream keeps a
stable `controllerAgentRef` (the Phase 5 registry field) and binds it to **at most one active
controller case** at a time. Starting binds the first case; **explicit compaction** archives the
current case and binds a fresh one with the same agent and workstream identity, the next sequence
number, and a **bounded resumption context package** rebuilt from the Phase 5 aggregated projection —
never a raw conversation history.

**Resiliency policy (modelled, KDoc-documented on `ControllerCaseService`):** the controller case
governs only conversational interlocution. If the Workstream Agent or its case is unavailable, the
underlying workflows, durable attempts, oracle executions and environments keep progressing under
their own policies — the service performs zero writes to those aggregates and holds no lease over
them. Only new conversational interactions wait for a case to be (re)bound. An integration test
proves the read-only invariant by snapshotting workflow/attempt revisions across start + compact.

## Files that carry it

Domain (`workstream/domain/`):
- `ControllerCaseExecution.kt` — immutable historical record of one case binding: `caseId`,
  `controllerAgentRef`, `status`, 1-based `sequence`, `startedAt`/`archivedAt`, `compactionReason`,
  `contextSummary` (bounded JSON), `contextRevision` (projection ETag provenance), plus
  `toDomain()`/`toNode()` mappers.
- `ControllerCaseStatus.kt` — `ACTIVE("active")` / `ARCHIVED("archived")` with case-insensitive
  `fromDbValue` (throws `INVALID_CONTROLLER_CASE_STATUS`).
- `ControllerCaseBounds.kt` — strict caps: `MAX_CONTEXT_SUMMARY_BYTES = 8192`,
  `MAX_WORKFLOW_ITEMS` / `MAX_HUMAN_ACTIONS` / `MAX_BLOCKERS` / `MAX_RECENT_CHANGES = 10`,
  `MAX_COMPACTION_REASON_CHARS = 500`.

Persistence (`workstream/persistence/`):
- `ControllerCaseExecutionNode.kt` — `@Node("ControllerCaseExecution")`, composite id
  `org|workstream|caseId`; defaulted properties, no migration (Neo4j schemaless).
- `SpringDataNeo4jControllerCaseRepository.kt` — Cypher queries: tenant-scoped active/history reads,
  `maxSequence`, idempotent `MERGE ... ON CREATE SET` start, and archive compare-and-set
  (`RETURN count(c)`).
- `Neo4jControllerCaseRepository.kt` — `@Transactional` write paths: `startFirst` (idempotent MERGE,
  sequence derived as `max+1`) and `archiveAndStart` (atomically archive active + bind new case,
  preserving `controllerAgentRef`). Single-active invariant enforced by these two paths only.

Resumption package (`workstream/projection/ControllerResumptionPackageBuilder.kt`):
- Maps the already-bounded `WorkstreamProjectionResponse` into a compact package (capped sections of
  active workflows, open human interactions, blockers = blocked steps + failed oracles, recent
  changes, plus authoritative uncapped `counts` and `sourceRevision` = projection ETag).
- `toBoundedJson` enforces the 8 KB UTF-8 cap compact-or-reject style: progressively drops
  `recentChanges` → `blockers` → `openHumanInteractions` → `activeWorkflows`, always keeping
  `counts` and `sourceRevision`.

Service (`workstream/ControllerCaseService.kt`):
- Every method first calls `workstreamService.assertWithinWorkstream` (trust boundary) and resolves
  the registry entry (404 `WORKSTREAM_NOT_FOUND`); identity comes from `caller.scope` only.
- `startControllerCase` — requires `controllerAgentRef` on the registry entry (422
  `CONTROLLER_AGENT_REF_REQUIRED`); 409 `CONTROLLER_CASE_ALREADY_ACTIVE` if one is active; persists
  the case with the bounded package.
- `compactControllerCase` — requires an active case (409 `NO_ACTIVE_CONTROLLER_CASE`); validates
  reason length (400 `INVALID_COMPACTION_REASON`); archives the old case stamping the reason, binds
  a new active case (next sequence, same `controllerAgentRef`, fresh package).
- `getActiveCase`, `listHistory`, `getContextPackage` (read-only preview, persists nothing).

Web (`workstream/web/ControllerCaseDtos.kt` + endpoints added to `workstream/WorkstreamController.kt`):
- `GET  /api/factory/workstreams/{id}/controller-case` — active case; explicit **404
  `NO_ACTIVE_CONTROLLER_CASE`** when none (never an empty 200).
- `GET  .../controller-case/history` — active + archived, ordered by sequence (`activeCaseId` field).
- `GET  .../controller-case/context?namespaceId=&limit=` — live bounded resumption package preview.
- `POST .../controller-case` — start the first case (201).
- `POST .../controller-case/compact` — explicit compaction/renewal (200, new active case).
- `ControllerCaseResponse` deliberately does **not** echo the `contextSummary` blob; the package is
  only re-derived live via the `/context` endpoint.

Spec: `specs/4c46f07d_controller_case_lifecycle.md` (the Phase 9 design contract).

## How to use / verify

Cockpit / API consumer flow:
1. Set `controllerAgentRef` on the workstream (Phase 5 `UpdateWorkstreamRequest`).
2. `POST .../controller-case` → 201, case 1 active. Re-posting without compaction → 409.
3. Optionally `GET .../controller-case/context` to preview what the next case will be seeded with.
4. `POST .../controller-case/compact` with `{ "compactionReason": "token budget" }` → new active
   case, sequence 2, same `controllerAgentRef`; the old case appears in `/history` as `archived`
   with `archivedAt` and the reason.

Tests (`factory-service/src/test/kotlin/io/whozoss/factory/workstream/`):
- `ControllerCaseServiceIntegrationTest` (9 tests): agent-ref requirement, double-start conflict,
  compaction semantics + history ordering, no-active-case rejection, bounded package (per-section
  caps, 8 KB cap, no conversation-text leak, provenance persisted), read-only invariant over
  workflows/attempts, tenant/workstream boundary (403 `WORKSTREAM_BOUNDARY_VIOLATION`), unknown
  workstream (404), repository start idempotence.
- `ControllerCaseControllerIntegrationTest` (6 tests): the five endpoints' HTTP contracts, error
  codes, and the boundary violation on every route.

Run: `pnpm nx test factory-service` (the factory also runs
`pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`).

## Boundaries respected

Only `factory-service/src/{main,test}/kotlin/io/whozoss/factory/workstream/` was touched (plus the
spec file). No changes to `agentattempt/`, `agentos/tools/`, `planchange`, no migrations, no release
scripts.
