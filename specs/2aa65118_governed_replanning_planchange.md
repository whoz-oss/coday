# Phase 8 — Governed Replanning & PlanChangeProposal

## Goal

Introduce a dedicated, governed replanning capability in `factory-service` via an
**append-only `PlanChangeProposal` store** plus **deterministic classification** and
**governance gating**, all under a NEW package
`io.whozoss.factory.planchange`. Expose a REST surface under
`/api/factory/plan-change-proposals`.

The Workstream Agent (and the human control-plane) **propose** plan changes; the Factory
**classifies deterministically, decides, and persists immutable records**. The feature NEVER
rewrites active workflow instances silently — any structural / contract / scope / DAG change
requires a human gate or a new workflow definition projection.

This plan touches ONLY the new package, plus two existing composition-root files that the
contract explicitly authorizes. See **Boundary Constraints** below.

---

## Strict Boundary Constraints (do NOT violate)

- Create a NEW package `io.whozoss.factory.planchange` with sub-packages `domain`,
  `persistence`, `service`, `web`.
- The ONLY existing files you may modify:
  - `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`
    — add `"io.whozoss.factory.planchange.persistence"` to `@EnableNeo4jRepositories.basePackages`.
  - `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`
    — add the `PlanChangeProposal` constraint + indexes.
- Do **NOT** modify anything under `factory-service/.../agentattempt/` (Phase 2 territory).
- Do **NOT** modify anything under `agentos/tools/` or the AgentOS plugins
  (`agentos/agentos-factory-bridge-plugin/`) — AgentOS plugin tools are Phase 6 parallel territory.
  (The `propose_plan_change` tool DTO in the contract doc is informational only; this phase delivers
  the HTTP + persistence + classification backend, not the AgentOS tool wiring.)
- Do NOT touch migrations or the release pipeline.
- Follow the identity/trust and error-envelope contracts in
  `app_docs/workstream_agent_cartography_and_contracts.md`.

---

## Context: existing patterns to copy

These are the reference files the builder should mirror for style, DI, scoping, and testing.
Read them before coding.

| Concern | Reference file |
|---|---|
| Tenant-scoped domain + node mapping | `workstream/domain/Workstream.kt`, `workstream/persistence/WorkstreamNode.kt` |
| Tenant-scoped repository adapter | `workstream/persistence/Neo4jWorkstreamRepository.kt` + `SpringDataNeo4jWorkstreamRepository.kt` |
| Append-only journal node (composite id + monotone `sequence` via `MAX(sequence)+1`) | `agentattempt/persistence/DurableAgentAttemptJournalNode.kt` + `SpringDataNeo4jDurableAgentAttemptJournalRepository.kt` |
| Idempotency record pattern | `agentattempt/persistence/Neo4jIdempotencyRepository.kt` |
| Composite-id node + idempotency property + CAS | `oracle/persistence/OracleExecutionNode.kt`, `oracle/persistence/Neo4jOracleExecutionRepository.kt` |
| Service w/ validation, bounds, revision conflict | `workstream/WorkstreamService.kt` |
| Controller w/ trust resolution + `DataEnvelope` | `oracle/web/OracleController.kt`, `workstream/WorkstreamController.kt` |
| HTTP helpers (`resolveFactoryCaller`, `factoryError`, `FactoryCaller`, `FactoryDataEnvelope`) | `web/FactoryHttp.kt` |
| Trust context | `web/TrustContext.kt`, `persistence/TenantScopeProvider.kt` |
| Error classes + stable envelope | `error/FactoryException.kt`, `error/FactoryExceptionHandler.kt` |
| TenantScope | `persistence/TenantScope.kt` |
| Canonical JSON hashing (for request/payload hash) | `agentattempt/domain/CanonicalJsonHash.kt` |
| Integration test base + token helper | `test/.../Neo4jIntegrationTest.kt`, `Neo4jDomainIntegrationTest.kt`, `web/TestJwt` usage in `workstream/WorkstreamControllerIntegrationTest.kt` |

**Key conventions observed in this codebase:**
- No semicolons, single quotes (N/A Kotlin), 120-char line limit, explicit types.
- Success envelope `{ "data": ... }` via a `DataEnvelope<T>` (oracle/workstream define their own;
  reuse `io.whozoss.factory.web.FactoryDataEnvelope<T>` from `FactoryHttp.kt`).
- Error envelope `{ "error": { code, message, details } }` is produced automatically by
  `FactoryExceptionHandler` whenever a `FactoryException` subtype is thrown. Throw
  `FactoryHttpException` via `factoryError(status, code, message, details)` or the typed
  exceptions in `error/FactoryException.kt`.
- Tenant scope is resolved at the boundary from the verified `TrustContext` via
  `resolveFactoryCaller(trustContext, tenantScopeProvider)` — NEVER from client input.
  Fails closed with `401 TRUST_CONTEXT_UNAVAILABLE`.
- Neo4j node `@Id` is a composite business key encoded as a single `"a|b|c"` string
  (impossible to access scope-less by construction).
- Append-only journals never update/delete; a mutable "current" node (if any) is separate.
- Schema constraints/indexes are added idempotently (`IF NOT EXISTS`) in `Neo4jSchemaInitializer`.

---

## Design decisions

### Immutability model
A `PlanChangeProposal` is **append-only and immutable** once created. The initial submission creates
the record with its deterministic classification and an initial decision status of
`PENDING_VALIDATION`. A later "decide" call does NOT mutate the original record: it **appends a new
immutable decision event node** (`PlanChangeDecisionNode`) linked by the proposal id and carrying a
monotone `sequence` (same `MAX(sequence)+1` pattern as the attempt journal). The proposal's current
decision status is derived by reading the latest decision event (ordered by `sequence DESC`), so
causality and history are preserved and the evidence never changes. This is the "preserve previous
plan history, causality, and evidence immutability" requirement.

> Rationale: mirrors the `DurableAgentAttemptJournal` approach (immutable transition log) and
> keeps the GET detail endpoint able to expose the full decision timeline.

### Idempotency
Idempotency key is `(organizationId, workstreamId, workflowId, idempotencyKey)` encoded as the
proposal node's composite id (or a denormalized indexed property with an existence check before
save, like `Neo4jIdempotencyRepository`). A second POST with the same tuple returns the already
persisted proposal (same `proposalId`, `idempotent: true`) instead of creating a duplicate. If a
replay arrives with a **different payload** for the same key, reject with
`IDEMPOTENCY_KEY_COLLISION` (409) — compute a canonical hash of the normalized payload with
`CanonicalJsonHash.hash(...)` and compare.

### Deterministic classification
Classification is a **pure function** `PlanChangeClassifier.classify(payload): PlanChangeKind`
with no I/O, no clock, no randomness — same input always yields the same taxonomy value. It is
driven solely by the submitted `reasonCode` + the shape of the proposed changes
(`proposedDependencyChanges`, `proposedScopeChanges`, `affectedStepIds`, and a `proposalType`
discriminator in the payload). This is the primary unit-tested surface.

### Governance gate derivation
A second pure function `GovernanceGateEvaluator.evaluate(kind, payload): GateVerdict` maps the
classification to one of the decision outcomes (Rules 1–3). It decides whether the change may be
**AUTO_APPLIED** (only pre-declared variations), requires a **GATE_REQUIRED** (human governance),
or **REQUIRES_NEW_DEFINITION** (successor workflow projection), or is **REJECTED**.

---

## Taxonomy & governance mapping (authoritative table)

`PlanChangeKind` enum values and their default gate verdict:

| Kind | Meaning | Default verdict (Rules 1–3) |
|---|---|---|
| `RETRY_NO_PLAN_CHANGE` | Simple retry, no structural change | `AUTO_APPLIED` (within definition limits) |
| `PATH_SELECTION` | Select a pre-declared pathway/branch in current definition | `AUTO_APPLIED` |
| `OPTIONAL_STEP_ACTIVATION` | Activate a pre-declared optional step | `AUTO_APPLIED` |
| `DEPENDENCY_CHANGE_PROPOSAL` | Add/remove dependency between existing steps | `GATE_REQUIRED` (DAG change) |
| `SCOPE_CHANGE_PROPOSAL` | Modify scope | `GATE_REQUIRED` (scope expansion ⇒ human) |
| `NEW_STEP_PROPOSAL` | Propose a new step | `REQUIRES_NEW_DEFINITION` |
| `CONTRACT_OR_ORACLE_CHANGE_PROPOSAL` | Modify step contract, schema, or oracle | `REQUIRES_NEW_DEFINITION` |

Governance rules encoded in `GovernanceGateEvaluator`:
- **Rule 1 — self-application** only for `RETRY_NO_PLAN_CHANGE`, `PATH_SELECTION`,
  `OPTIONAL_STEP_ACTIVATION` ⇒ `AUTO_APPLIED`. These must reference ONLY pre-declared variations.
- **Rule 2 — structural/contract/scope/DAG** (`NEW_STEP_PROPOSAL`, `DEPENDENCY_CHANGE_PROPOSAL`,
  `SCOPE_CHANGE_PROPOSAL`, `CONTRACT_OR_ORACLE_CHANGE_PROPOSAL`) ⇒ never auto-apply; requires human
  gate (`GATE_REQUIRED`) or new/successor definition (`REQUIRES_NEW_DEFINITION`). Active instances
  are never silently rewritten.
- **Rule 3 — human gate** mandatory for scope expansion, deny weakening, or governance policy
  changes ⇒ `GATE_REQUIRED` at minimum (never `AUTO_APPLIED`).

Decision outcomes the `/decide` endpoint can record: `AUTO_APPLIED`, `GATE_REQUIRED`,
`REQUIRES_NEW_DEFINITION`, `REJECTED`. The submit endpoint computes the deterministic
classification + a **recommended** verdict; `/decide` records the human/control-plane applied
decision (which must be consistent with the governance rules — e.g. `/decide` MUST reject an attempt
to record `AUTO_APPLIED` for a Rule-2 kind with `PLAN_CHANGE_GATE_REQUIRED`).

---

## Files to create

All under `factory-service/src/main/kotlin/io/whozoss/factory/planchange/`.

### domain/
1. **`PlanChangeKind.kt`** — enum of the 7 taxonomy values + `fromDbValue`/`dbValue` helpers
   (mirror `WorkstreamStatus` style).
2. **`PlanChangeDecisionStatus.kt`** — enum: `PENDING_VALIDATION`, `AUTO_APPLIED`, `GATE_REQUIRED`,
   `REQUIRES_NEW_DEFINITION`, `REJECTED` + db-value helpers.
3. **`PlanChangeProposal.kt`** — immutable domain data class. Fields:
   - Tenant scope: `organizationId`, `workstreamId`, `namespaceId`.
   - `proposalId: String`, `workflowId: String`, `expectedRevision: Int`.
   - `reasonCode: String`, `summary: String`.
   - `proposalType: PlanChangeProposalType` (discriminator; see enum below).
   - `affectedStepIds: List<String>`.
   - `proposedDependencyChanges: List<DependencyChange>` (op=add/remove, fromStepId, toStepId).
   - `proposedScopeChanges: List<ScopeChange>?` (optional).
   - `evidenceRefs: List<String>`.
   - `idempotencyKey: String`.
   - `kind: PlanChangeKind` (computed classification), `recommendedVerdict: PlanChangeDecisionStatus`.
   - `currentStatus: PlanChangeDecisionStatus`, `revision: Int`, `createdAt`, `updatedAt`.
   - `toNode()` / `PlanChangeProposalNode.toDomain()` mapping functions in this file (mirror
     `Workstream.kt`).
4. **`PlanChangeProposalType.kt`** — enum discriminator the submitter sends to make classification
   deterministic (e.g. `RETRY`, `PATH_SELECTION`, `OPTIONAL_STEP`, `DEPENDENCY`, `SCOPE`, `NEW_STEP`,
   `CONTRACT_OR_ORACLE`). Classification combines this with the payload shape.
5. **`PlanChangeModels.kt`** — supporting immutable value types: `DependencyChange`
   (`op: DependencyOp {ADD, REMOVE}`, `fromStepId`, `toStepId`), `ScopeChange`
   (`op`, `target`, `detail`), `PlanChangeDecision` (one immutable decision event:
   `sequence`, `status`, `actorId`, `reason`, `recordedAt`).
6. **`PlanChangeClassifier.kt`** — `object` with pure `classify(...)` → `PlanChangeKind`.
   Deterministic rules (see taxonomy table). No I/O. Fully unit-tested.
7. **`GovernanceGateEvaluator.kt`** — `object` with pure `evaluate(kind, payload)` →
   `PlanChangeDecisionStatus` (recommended verdict) + a `assertDecisionAllowed(kind, requested)`
   that throws `PLAN_CHANGE_GATE_REQUIRED` (409) when a human/control-plane decision would violate
   Rules 1–3 (e.g. auto-applying a structural change). No I/O.
8. **`PlanChangeValidation.kt`** — bounded validation of the payload (max lengths, list caps,
   `additionalProperties`-equivalent strictness). Mirror `AgentStepResultValidation` strictness and
   `WorkstreamBounds` cap style. Rejects with `INVALID_PLAN_CHANGE_PROPOSAL` (400).
9. **`PlanChangeExceptions.kt`** — typed `FactoryException` subclasses or thin factory funcs for the
   plan-change error codes (see Error codes section). Prefer reusing `factoryError(...)` from
   `FactoryHttp.kt`; add typed exceptions only where a controller/service needs to catch them.

### persistence/
10. **`PlanChangeProposalNode.kt`** — `@Node("PlanChangeProposal")`. `@Id id` =
    `compositeId(organizationId, workstreamId, namespaceId, workflowId, proposalId)`. Properties:
    all scalar fields of the domain model; store list/object fields (`affectedStepIds`,
    `proposedDependencyChanges`, `proposedScopeChanges`, `evidenceRefs`) as JSON strings via
    `ObjectMapper` (like `OracleExecutionNode.payload`) OR as native Neo4j list properties for the
    simple `List<String>` ones; keep the complex object lists as a serialized `payloadJson` plus a
    denormalized `idempotencyKey` property for indexed lookup. Add `requestHash` (canonical hash)
    for idempotency collision detection. `compositeId` companion + `toDomain()`/`fromDomain()`.
11. **`PlanChangeDecisionNode.kt`** — `@Node("PlanChangeDecision")`. Append-only decision event.
    `@Id id` = `compositeId(organizationId, workstreamId, namespaceId, workflowId, proposalId,
    sequence)`. Properties: `sequence: Long`, `status`, `actorId`, `reason`, `recordedAt`, plus the
    scope + `proposalId` fields for scoped queries. Mirror `DurableAgentAttemptJournalNode`.
12. **`SpringDataNeo4jPlanChangeProposalRepository.kt`** — `Neo4jRepository<PlanChangeProposalNode,
    String>` with `@Query` methods: `findByWorkflow(org, ws, ns, workflowId)` ordered by
    `createdAt`, `findByIdempotency(org, ws, workflowId, idempotencyKey)`, and optionally
    `findByWorkflowAndStatus(...)`.
13. **`SpringDataNeo4jPlanChangeDecisionRepository.kt`** — `Neo4jRepository<PlanChangeDecisionNode,
    String>` with `maxSequence(...)` (`coalesce(max(sequence),0)`) and
    `findByProposal(...)` ordered `sequence ASC` (mirror the attempt-journal repo).
14. **`Neo4jPlanChangeProposalRepository.kt`** — `@Repository @Primary` tenant-scoped adapter.
    Methods: `create(scope, proposal): PlanChangeProposal`, `findById(scope, workflowId, proposalId)`,
    `findByWorkflow(scope, workflowId, status?)`, `findByIdempotency(scope, workflowId,
    idempotencyKey)`, `appendDecision(scope, proposal, decision): PlanChangeProposal`
    (writes an immutable `PlanChangeDecisionNode` via `maxSequence+1` AND bumps the proposal's
    `currentStatus`/`revision`/`updatedAt` — note: `currentStatus` is a derived cache, the decision
    log is the source of truth), `listDecisions(scope, workflowId, proposalId)`. Every query
    constrained by `scope.organizationId`/`scope.workstreamId` + `takeIf` guard, mirroring
    `Neo4jWorkstreamRepository` / `Neo4jOracleExecutionRepository`.

### service/
15. **`PlanChangeProposalService.kt`** — `@Service`. Orchestrates:
    - `submit(scope, actorId, command): PlanChangeSubmitResult` — validate payload
      (`PlanChangeValidation`), classify (`PlanChangeClassifier`), compute recommended verdict
      (`GovernanceGateEvaluator`), check idempotency (return existing on key match, reject on
      collision), persist immutable proposal (`PENDING_VALIDATION`) + an initial decision event
      capturing the recommended verdict as `PENDING_VALIDATION`. Returns proposal + `idempotent`
      flag.
    - `list(scope, workflowId, status?): List<PlanChangeProposal>`.
    - `get(scope, workflowId, proposalId): PlanChangeProposal` (404
      `PLAN_CHANGE_PROPOSAL_NOT_FOUND`).
    - `decide(scope, actorId, workflowId, proposalId, decision): PlanChangeProposal` — load proposal,
      enforce `expectedRevision` fence (`REVISION_CONFLICT`), call
      `GovernanceGateEvaluator.assertDecisionAllowed(kind, requestedStatus)` (throws
      `PLAN_CHANGE_GATE_REQUIRED` 409 when the requested outcome violates Rules 1–3), append an
      immutable decision event, return updated proposal.
    - Enforce the trust boundary: the `workflowId` and scope come from the resolved caller; the
      proposal `workstreamId` MUST equal `scope.workstreamId`.

### web/
16. **`PlanChangeDtos.kt`** — request/response DTOs + bounds object:
    - `PlanChangeBounds` (MAX_SUMMARY=2000, MAX_AFFECTED_STEPS=100, MAX_DEPENDENCY_CHANGES=50,
      MAX_SCOPE_CHANGES=50, MAX_EVIDENCE_REFS=100, MAX_REASON_CODE=64, MAX_IDEMPOTENCY_KEY=128,
      MAX_STEP_ID=128 — align with the contract doc's bounded schemas).
    - `SubmitPlanChangeRequest` (nullable fields so a malformed body is a clean 400):
      `workflowId`, `expectedRevision`, `reasonCode`, `summary`, `proposalType`, `affectedStepIds`,
      `proposedDependencyChanges`, `proposedScopeChanges`, `evidenceRefs`, `idempotencyKey`.
    - `DependencyChangeDto`, `ScopeChangeDto`.
    - `DecidePlanChangeRequest`: `expectedRevision`, `decision`
      (`AUTO_APPLIED|GATE_REQUIRED|REQUIRES_NEW_DEFINITION|REJECTED`), `reason`, `idempotencyKey?`.
    - `PlanChangeProposalResponse`: `proposalId`, `workflowId`, `namespaceId`, `reasonCode`,
      `summary`, `kind`, `recommendedVerdict`, `status`, `affectedStepIds`,
      `proposedDependencyChanges`, `proposedScopeChanges`, `evidenceRefs`, `revision`,
      `idempotent`, `createdAt`, `updatedAt`, `decisions: List<PlanChangeDecisionResponse>`.
    - `PlanChangeDecisionResponse`: `sequence`, `status`, `actorId`, `reason`, `recordedAt`.
    - Mapper funcs domain→response (secret-free; no payload hash leaked).
17. **`PlanChangeProposalController.kt`** — `@RestController @RequestMapping(
    "/api/factory/plan-change-proposals")`. Endpoints:
    - `POST /` → `submit`. Returns `201` (new) / `200` (idempotent replay) with
      `FactoryDataEnvelope<PlanChangeProposalResponse>`.
    - `GET /?workflowId=...&status=...` → `list`. Returns `data: [ ... ]`. `workflowId` required
      (400 `INVALID_PLAN_CHANGE_QUERY` if missing).
    - `GET /{proposalId}?workflowId=...` → `get`.
    - `POST /{proposalId}/decide` → `decide`.
    - Resolve caller with `resolveFactoryCaller(trustContext, tenantScopeProvider)`. Inject
      `TrustContext?` as a hidden `@Parameter`. The `namespaceId` comes from the body/query and is
      validated.
    - All failures flow through `FactoryExceptionHandler` automatically.

> Note on namespace: follow whatever the workflow surface uses. The contract requires TenantScope
> `(organizationId, workstreamId, namespaceId)`; `organizationId`+`workstreamId` come from the
> trusted scope, `namespaceId` comes from the request body/query and is validated non-blank
> (`INVALID_NAMESPACE_ID`), consistent with `OracleController`.

---

## Files to modify (ONLY these two)

### `config/Neo4jPersistenceConfiguration.kt`
Add one entry to the `@EnableNeo4jRepositories(basePackages = [...])` array:
```kotlin
"io.whozoss.factory.planchange.persistence",
```
Place it after `"io.whozoss.factory.workstream.persistence",`.

### `config/Neo4jSchemaInitializer.kt`
Add a new block (idempotent, `IF NOT EXISTS`), after the Human-interaction-event block and before
the final `logger.info`:
```kotlin
// ── Plan change proposals (append-only governed replanning) ────────
neo4jClient.query(
    "CREATE CONSTRAINT plan_change_proposal_id_unique IF NOT EXISTS " +
        "FOR (p:PlanChangeProposal) REQUIRE p.id IS UNIQUE",
).run()
neo4jClient.query(
    "CREATE INDEX plan_change_proposal_workflow IF NOT EXISTS " +
        "FOR (p:PlanChangeProposal) ON " +
        "(p.organizationId, p.workstreamId, p.namespaceId, p.workflowId, p.status)",
).run()
neo4jClient.query(
    "CREATE INDEX plan_change_proposal_idempotency IF NOT EXISTS " +
        "FOR (p:PlanChangeProposal) ON " +
        "(p.organizationId, p.workstreamId, p.workflowId, p.idempotencyKey)",
).run()
// Append-only decision log
neo4jClient.query(
    "CREATE CONSTRAINT plan_change_decision_id_unique IF NOT EXISTS " +
        "FOR (d:PlanChangeDecision) REQUIRE d.id IS UNIQUE",
).run()
neo4jClient.query(
    "CREATE INDEX plan_change_decision_proposal IF NOT EXISTS " +
        "FOR (d:PlanChangeDecision) ON " +
        "(d.organizationId, d.workstreamId, d.namespaceId, d.workflowId, d.proposalId)",
).run()
```
Add matching `logger.debug { ... }` lines after each, matching the surrounding style.

---

## Error codes (stable; thrown as `FactoryException` / `factoryError`)

| Code | HTTP | When |
|---|---|---|
| `INVALID_PLAN_CHANGE_PROPOSAL` | 400 | Malformed body / unknown fields / bound exceeded / unknown `proposalType` or `reasonCode` shape |
| `INVALID_PLAN_CHANGE_QUERY` | 400 | Missing/blank required `workflowId` query param on list/get |
| `INVALID_PLAN_CHANGE_DECISION` | 400 | Unknown decision value in `/decide` body |
| `INVALID_NAMESPACE_ID` | 400 | Missing/blank namespace |
| `TRUST_CONTEXT_UNAVAILABLE` | 401 | No verified trust context (from `resolveFactoryCaller`) |
| `WORKSTREAM_BOUNDARY_VIOLATION` | 403 | Proposal references a workstream outside the trusted scope |
| `PLAN_CHANGE_PROPOSAL_NOT_FOUND` | 404 | `get`/`decide` on an unknown proposal |
| `REVISION_CONFLICT` | 409 | `expectedRevision` fence stale on submit/decide |
| `IDEMPOTENCY_KEY_COLLISION` | 409 | Same idempotency tuple, different canonical payload |
| `PLAN_CHANGE_GATE_REQUIRED` | 409 | `/decide` tries to record an outcome that violates Rules 1–3 (e.g. auto-apply a structural change) |

Reuse existing `REVISION_CONFLICT` via `RevisionConflictException`, and the 401/403 patterns from
`FactoryHttp.kt` / `WorkstreamService.assertWithinWorkstream`.

---

## Testing & verification

Create tests under `factory-service/src/test/kotlin/io/whozoss/factory/planchange/`.

### Pure unit tests (no Spring context — plain JUnit)
1. **`PlanChangeClassifierTest.kt`** — one assertion per taxonomy value proving determinism:
   same input ⇒ same `PlanChangeKind`; and that `proposalType` + payload shape map to the correct
   kind for all 7 cases. Include a "same input twice ⇒ identical result" determinism check.
2. **`GovernanceGateEvaluatorTest.kt`** — assert recommended verdict for each kind (Rules 1–3):
   auto-applicable kinds ⇒ `AUTO_APPLIED`; structural/dependency/scope ⇒ `GATE_REQUIRED`;
   new-step/contract-oracle ⇒ `REQUIRES_NEW_DEFINITION`; `assertDecisionAllowed` throws
   `PLAN_CHANGE_GATE_REQUIRED` when auto-applying a Rule-2 kind, and permits `REJECTED` for any kind.
3. **`PlanChangeValidationTest.kt`** — bound violations (summary too long, too many steps, unknown
   field) rejected; a valid payload accepted.

### Integration tests (`Neo4jDomainIntegrationTest` / `Neo4jIntegrationTest`, embedded Neo4j, no Docker)
4. **`PlanChangeProposalRepositoryIntegrationTest.kt`** (extends `Neo4jDomainIntegrationTest`):
   - create + read within tenant scope; reads constrained by scope (other org/ws sees nothing).
   - idempotency: second `create` with same tuple returns the first (via service), different payload
     ⇒ `IDEMPOTENCY_KEY_COLLISION`.
   - `appendDecision` writes an immutable decision with monotone `sequence`, preserves the original
     proposal evidence/fields, bumps `currentStatus`/`revision`; `listDecisions` ordered ascending.
5. **`PlanChangeProposalControllerIntegrationTest.kt`** (extends `Neo4jIntegrationTest`, uses
   `TestRestTemplate` + `TestJwt` like `WorkstreamControllerIntegrationTest`):
   - `POST` submits, returns 201, deterministic `kind`+`recommendedVerdict`, status
     `PENDING_VALIDATION`.
   - `POST` replay with identical body ⇒ 200, `idempotent: true`, same `proposalId`.
   - `POST` replay with same key + different body ⇒ 409 `IDEMPOTENCY_KEY_COLLISION`.
   - `GET /?workflowId=` lists; filter by `status`.
   - `GET /{proposalId}` returns details incl. decision timeline.
   - `POST /{proposalId}/decide` with `GATE_REQUIRED` for a dependency-change proposal ⇒ 200,
     status updated, decision appended.
   - `POST /{proposalId}/decide` trying `AUTO_APPLIED` on a `NEW_STEP_PROPOSAL` ⇒ 409
     `PLAN_CHANGE_GATE_REQUIRED`.
   - stale `expectedRevision` on decide ⇒ 409 `REVISION_CONFLICT`.
   - missing/blank trust context ⇒ 401 `TRUST_CONTEXT_UNAVAILABLE`.
   - a `workflowId`/proposal outside the trusted scope ⇒ 404 or 403 as appropriate.

### How to run
From the repo root (factory affected target wraps Gradle):
```
pnpm nx test factory-service
```
or directly:
```
cd factory-service && ./gradlew test
```
The factory CI also runs the affected suite:
`pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`.

Optional quality gates:
```
pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
```

> Note: there is NO `justfile` in this repo; the `just test` reference in the prompt maps to the
> Gradle/Nx commands above. Also note `factory-service` has a `check-openapi-spec` target; adding a
> new controller changes the generated OpenAPI. The builder should regenerate it if the OpenAPI
> check is part of the affected gates: `pnpm nx generate-openapi-spec factory-service` (or
> `cd factory-service && ./gradlew generateOpenApiDocs --no-configuration-cache`) and commit the
> updated `factory-service/openapi/factory-openapi.yaml`. Only do this if the check fails in the
> factory gates — do not hand-edit the YAML.

---

## Verification checklist for the builder (Build Claims)

In the build claims, list the ACTUAL exact file paths created/modified. Expected set:

**Created (relative to repo root):**
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeKind.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeDecisionStatus.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeProposalType.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeModels.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeProposal.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeClassifier.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/GovernanceGateEvaluator.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeValidation.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/domain/PlanChangeExceptions.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/persistence/PlanChangeProposalNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/persistence/PlanChangeDecisionNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/persistence/SpringDataNeo4jPlanChangeProposalRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/persistence/SpringDataNeo4jPlanChangeDecisionRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/persistence/Neo4jPlanChangeProposalRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/service/PlanChangeProposalService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/web/PlanChangeDtos.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/planchange/web/PlanChangeProposalController.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/planchange/PlanChangeClassifierTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/planchange/GovernanceGateEvaluatorTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/planchange/PlanChangeValidationTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/planchange/PlanChangeProposalRepositoryIntegrationTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/planchange/PlanChangeProposalControllerIntegrationTest.kt`

**Modified (ONLY these):**
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`
- (possibly) `factory-service/openapi/factory-openapi.yaml` — only if the OpenAPI check gate requires
  regeneration; regenerate via Gradle, never hand-edit.

**Done when:**
- New package compiles; `./gradlew test` (or `pnpm nx test factory-service`) passes clean.
- Classification is deterministic and unit-proven for all 7 taxonomy values.
- Governance gates enforce Rules 1–3 (no silent rewrite of active instances; structural/scope/
  contract changes gated).
- Proposals + decisions are immutable and append-only; history/causality/evidence preserved.
- Idempotency by `(organizationId, workstreamId, workflowId, idempotencyKey)` works, collisions
  rejected.
- Endpoints behave per the REST surface spec; trust boundary enforced; error envelope matches the
  stable codes.
- No file under `agentattempt/` or `agentos/` was touched.
