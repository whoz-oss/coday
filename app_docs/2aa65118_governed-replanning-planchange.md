# Phase 8 — Governed Replanning & PlanChangeProposal

Base: `1daa15cd` — 26 files changed, +4704 / −98. Spec: `specs/2aa65118_governed_replanning_planchange.md`.

## What changed and why

The Factory now has a dedicated, governed replanning capability. Workstream Agents
(and the human control plane) can **propose** plan changes against a workflow; the
Factory validates the payload, **classifies it deterministically**, computes a
**recommended governance verdict**, and persists everything in an **append-only
store**. Nothing on this surface ever rewrites an active workflow instance
silently: structural, contract, oracle, scope or DAG changes require a human
governance gate or a new workflow definition / successor projection.

Everything lives in a NEW package `io.whozoss.factory.planchange` (domain /
persistence / service / web). The only pre-existing files touched are the two
composition-root files the boundary explicitly authorizes — plus the regenerated
OpenAPI spec. `agentattempt/` and `agentos/tools/` are untouched, per the
boundary constraints.

## How it works

**Submission flow** (`POST /api/factory/plan-change-proposals`):
1. Trust resolution at the boundary (`resolveFactoryCaller`) — the
   `(organizationId, workstreamId)` scope always comes from the verified caller,
   never from client input; `namespaceId` is supplied and strictly validated.
2. `PlanChangeValidation.validateSubmit` — pure, bounded validation (bounds in
   `PlanChangeBounds`). Enforces strict type/shape coherence: a `DEPENDENCY`
   proposal must carry dependency changes and cannot carry scope changes, a
   benign declared type (`RETRY`, `PATH_SELECTION`, …) cannot smuggle structural
   payloads, a `RETRY` must reference at least one affected step, a dependency
   edge cannot reference the same step twice.
3. `PlanChangeClassifier.classify` — pure function mapping (declared
   `PlanChangeProposalType` + payload shape) to the `PlanChangeKind` taxonomy:
   `RETRY_NO_PLAN_CHANGE`, `PATH_SELECTION`, `OPTIONAL_STEP_ACTIVATION`,
   `DEPENDENCY_CHANGE_PROPOSAL`, `SCOPE_CHANGE_PROPOSAL`, `NEW_STEP_PROPOSAL`,
   `CONTRACT_OR_ORACLE_CHANGE_PROPOSAL`. Most-structural signal wins, so a
   structural payload can never be downgraded by a benign declared type.
4. `GovernanceGateEvaluator.recommendedVerdict` — deterministic verdict:
   - Rule-1 self-applicable kinds (retry, path selection, optional-step
     activation) → `AUTO_APPLIED`;
   - dependency / scope changes → `GATE_REQUIRED` (Rules 2–3);
   - new-step / contract-or-oracle changes → `REQUIRES_NEW_DEFINITION` (Rule 2).
5. Idempotency on the tuple `(organizationId, workstreamId, workflowId,
   idempotencyKey)`: the normalized payload is hashed with
   `CanonicalJsonHash` (SHA-256); a replay with the same hash returns the
   persisted proposal (`idempotent: true`, HTTP 200), a divergent payload is a
   409 `IDEMPOTENCY_KEY_COLLISION`.
6. The proposal is persisted with status `PENDING_VALIDATION`, and an initial
   immutable decision event records the submission and the computed
   classification/verdict, so the decision log is complete from the start.

**Decision flow** (`POST /api/factory/plan-change-proposals/{proposalId}/decide`):
- Body: `expectedRevision` (mandatory optimistic-locking fence — a stale value is
  a 409 `REVISION_CONFLICT`), `decision` (one of `AUTO_APPLIED`, `GATE_REQUIRED`,
  `REQUIRES_NEW_DEFINITION`, `REJECTED`), optional `reason` and `idempotencyKey`.
- `GovernanceGateEvaluator.assertDecisionAllowed` enforces Rules 1–3:
  `AUTO_APPLIED` is only recordable for Rule-1 self-applicable kinds; anything
  else attempting auto-apply is rejected with 409 `PLAN_CHANGE_GATE_REQUIRED`.
  `REJECTED`, `GATE_REQUIRED` and `REQUIRES_NEW_DEFINITION` are recordable for
  any kind. `PENDING_VALIDATION` is never a recordable decision.
- A `/decide` replay carrying the same idempotency key as the latest decision
  returns current state without appending a duplicate (divergent decision on the
  same key → 409 collision).

**Read endpoints**: `GET /api/factory/plan-change-proposals` (requires
`workflowId` and `namespaceId` query params; optional `status` filter on the
derived current status) and `GET /api/factory/plan-change-proposals/{proposalId}`
(full detail including the complete decision timeline). Success responses use the
canonical `{ "data": … }` envelope; failures use the standard
`{ "error": { code, message, details } }` envelope via `FactoryHttpException`.

## Persistence model (append-only)

Two Neo4j node types, both with composite string ids encoding the full tenant
scope so a scope-less access is impossible by construction:

- `PlanChangeProposal` — id
  `org|workstream|namespace|workflow|proposalId`. The submitted payload
  (reason, summary, affected steps, proposed changes as serialized JSON, evidence
  refs, idempotency key, `requestHash`) plus the immutable classification
  (`kind`, `recommendedVerdict`) are written once and never updated. Only the
  derived cache (`currentStatus`, `revision`, `updatedAt`) is maintained — its
  source of truth is the decision log.
- `PlanChangeDecision` — id `…|proposalId|sequence`. One immutable node per
  decision event; `sequence` is generated graph-natively as `MAX(sequence) + 1`
  (same pattern as the attempt journal). Events are never updated nor deleted,
  preserving plan history, causality and evidence immutability.

`Neo4jSchemaInitializer` ensures: unique constraints on both node ids, a workflow
lookup index `(organizationId, workstreamId, namespaceId, workflowId,
currentStatus)`, an idempotency index `(organizationId, workstreamId, workflowId,
idempotencyKey)`, and a decision lookup index `(organizationId, workstreamId,
namespaceId, workflowId, proposalId)`.
`Neo4jPersistenceConfiguration` registers `io.whozoss.factory.planchange.persistence`
in `@EnableNeo4jRepositories`.

## Files that carry it

New package `factory-service/src/main/kotlin/io/whozoss/factory/planchange/`:
- `domain/` — `PlanChangeKind`, `PlanChangeProposalType`, `PlanChangeDecisionStatus`
  (taxonomy + enums with stable wire/db values), `PlanChangeModels` (`DependencyChange`,
  `ScopeChange`, `PlanChangeDecision`, submit/decide commands), `PlanChangeProposal`
  (immutable aggregate + node mapping), `PlanChangeClassifier`,
  `GovernanceGateEvaluator`, `PlanChangeValidation`, `PlanChangeExceptions`
  (stable error codes: `INVALID_PLAN_CHANGE_PROPOSAL`, `INVALID_PLAN_CHANGE_QUERY`,
  `INVALID_PLAN_CHANGE_DECISION`, `INVALID_NAMESPACE_ID`,
  `PLAN_CHANGE_PROPOSAL_NOT_FOUND`, `IDEMPOTENCY_KEY_COLLISION`,
  `PLAN_CHANGE_GATE_REQUIRED`).
- `persistence/` — `PlanChangeProposalNode`, `PlanChangeDecisionNode`, the two
  Spring Data repositories, and `Neo4jPlanChangeProposalRepository` (`@Primary`
  adapter: create, tenant-scoped reads, idempotency lookup, `appendDecision` with
  `MAX(sequence)+1` and derived-cache refresh).
- `service/PlanChangeProposalService.kt` — orchestration (validation →
  classification → verdict → idempotency → persist; revision fence + gate
  enforcement on decide).
- `web/` — `PlanChangeDtos` (strict request DTOs rejecting unknown JSON fields,
  `PlanChangeBounds`, response DTOs; the canonical request hash is never exposed)
  and `PlanChangeProposalController` (the four endpoints).

Modified pre-existing files:
- `config/Neo4jPersistenceConfiguration.kt` — one added base package.
- `config/Neo4jSchemaInitializer.kt` — plan-change constraints and indexes.
- `openapi/factory-openapi.yaml` — regenerated spec: adds the
  `plan-change-proposals` tag, the four new paths and the
  `SubmitPlanChangeRequest` / `DecidePlanChangeRequest` /
  `PlanChangeProposalResponse` / `PlanChangeDecisionResponse` /
  `DependencyChangeDto` / `ScopeChangeDto` schemas. The regeneration also now
  documents previously-undocumented existing endpoints (workstream get/put and
  projection, workflow attempts/actions/session, attempt cancel, agent-step
  questions and answers, workflow-definition upload/delete, step-result bindings,
  `/api/namespaces`, cost stop/continue).

Tests (`factory-service/src/test/kotlin/io/whozoss/factory/planchange/`):
- `PlanChangeClassifierTest` — every taxonomy branch, determinism, no-downgrade.
- `PlanChangeValidationTest` — bounds, coherence rules, self-referencing edges.
- `GovernanceGateEvaluatorTest` — recommended verdicts per rule, `AUTO_APPLIED`
  gating (409), non-decidability of `PENDING_VALIDATION`.
- `PlanChangeProposalRepositoryIntegrationTest` — tenant-scope isolation,
  idempotent replay vs collision, monotone immutable decision log, status filter.
- `PlanChangeProposalControllerIntegrationTest` — end-to-end submit/list/get/
  decide, gate enforcement on `NEW_STEP`, Rule-1 auto-apply, revision conflict,
  query validation, unknown-field rejection, trust-context failure.

## Verify

```
pnpm nx test factory-service          # or: cd factory-service && ./gradlew test
```

Manual smoke (with the service running and a trusted caller token):
```
curl -X POST /api/factory/plan-change-proposals \
  -d '{"workflowId":"wf-1","namespaceId":"ns","expectedRevision":1,
       "reasonCode":"STEP_FAILED","summary":"retry build","proposalType":"RETRY",
       "affectedStepIds":["build"],"idempotencyKey":"k-1"}'
# → 201, kind=RETRY_NO_PLAN_CHANGE, recommendedVerdict=AUTO_APPLIED
# re-POST the identical body → 200 with "idempotent": true
# POST /{proposalId}/decide with {"expectedRevision":1,"decision":"AUTO_APPLIED"} → appended decision, revision 2
# the same AUTO_APPLIED decision on a NEW_STEP proposal → 409 PLAN_CHANGE_GATE_REQUIRED
```

## Notes for the next engineer

- The service **only records** proposals and decisions; nothing here applies a
  change to a running workflow. Rule-2/3 outcomes (`GATE_REQUIRED`,
  `REQUIRES_NEW_DEFINITION`) are landing pads for a human gate UI or a successor
  definition projection — not yet wired.
- The AgentOS `propose_plan_change` plugin tool is explicitly out of scope for
  this phase (Phase 6 territory); only the HTTP + persistence + classification
  backend shipped.
- `AUTO_APPLIED` decisions are recorded, but the actual self-application of
  pre-declared variations to the workflow instance is not performed by this
  surface.
