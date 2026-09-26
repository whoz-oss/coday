# Forge Ledger → Generic Workflow Mapping (Step 1)

Read-only cartography of the Forge Ledger JSONL journal onto the Coday generic
workflow vocabulary. **Authority does not move in Step 1**: the Forge ledger
(`factory/src/domain/forge-bmad/forge-ledger.ts` +
`factory/src/adapters/forge/forge-ledger-store.ts`, facade
`factory/lib/forge-ledger.mjs`) remains primary. This document and its
implementation are *read-only*: no write path, no generic store, no SQL, no
generated runtime bundle is touched.

- Projection adapter (pure, no I/O): `factory/src/domain/forge-bmad/forge-ledger-projection.ts`
- Offline test suite: `factory/tests/test-forge-ledger-projection.mjs` (`node factory/tests/test-forge-ledger-projection.mjs`, exit 0/1)

## Scope boundaries (honoured)

| Not modified | Why |
|---|---|
| `factory/lib/forge-ledger.mjs` / `forge-ledger.ts` | Ledger authority stays Forge. |
| `FACT_KEYS` in `factory/src/domain/evidence/workflow-evidence.ts` | Only documented/flagged here; extended only in Step 2. |
| SQL migrations, SQL adapters, `factory/infra/migrations/` | Out of scope. |
| `factory/runtime/factory-operational.mjs` | Generated artefact, never hand-edited. |
| `agentos/**` | Out of scope. |

## 1. Source event inventory

Every event is one JSONL line with `schemaVersion: 1` and an `event` selector.
The eleven events named in the Step 1 brief are marked ★; the three additional
start events the writers actually emit are marked ☆ and are handled too (not
dropped), because a read-only projection must be exhaustive.

| Event | Writer | ★/☆ |
|---|---|---|
| `run_started` | `adapters/forge/forge-ledger-store.ts` (`createEpicRun`) | ★ |
| `story_run_created` | `adapters/forge/forge-ledger-store.ts` (`createEpicRun`) | ★ |
| `gate_started` | `adapters/forge/forge-ledger-store.ts` (`createEpicRun`) | ★ |
| `human_decision_recorded` | `application/forge-bmad/forge-human-decision.ts` | ★ |
| `g2_evaluated` | `application/forge-bmad/forge-g2.ts` | ★ |
| `g2_us_evaluated` | `application/forge-bmad/forge-g2.ts` | ★ |
| `agent_execution_started` | `application/forge-bmad/forge-story-analysis.ts` | ☆ |
| `agent_execution_finished` | `application/forge-bmad/forge-story-analysis.ts` | ★ |
| `story_analysis_plan_validated` | `application/forge-bmad/forge-story-analysis.ts` | ★ |
| `story_edit_started` | `application/forge-bmad/forge-story-edit.ts` | ☆ |
| `story_edit_finished` | `application/forge-bmad/forge-story-edit.ts` | ★ |
| `story_oracles_started` | `application/forge-bmad/forge-story-oracles.ts` | ☆ |
| `story_oracle_finished` | `application/forge-bmad/forge-story-oracles.ts` | ★ |
| `story_g3_evaluated` | `application/forge-bmad/forge-story-oracles.ts` | ★ |

Any other `event` value is reported by the projector with reason
`unmapped_event_type`.

## 2. Generic target model (recap)

**(a) Workflow state / step transitions (V3).** Allowed `WorkflowStatus` values
(`domain/workflow/workflow-transition-policy.ts`): `pending`, `ready`,
`running`, `waiting_human`, `blocked`, `completed`, `failed`, `cancelled`.

**(b) `workflow_evidence` (V5).** Kinds: `agent-result`, `artifact`,
`oracle-result`, `human-decision`. Outcomes: `pass`, `fail`, `indeterminate`.
`artifact` requires `artifactRef` + `artifactHash` (`sha256:<64 hex>`) and
carries no facts/outcome. Facts must use the `FACT_KEYS` whitelist below.

**(c) `human_interactions` (V5).** Opening (`kind: approval|choice|text`,
exactly two actions, `expectedRevision >= 1`) and reply (`actorId`, action,
`repliedAt`).

### Current `FACT_KEYS` whitelist (reference, unchanged)

`resultCode`, `category`, `attempt`, `durationMs`, `itemCount`, `oracleId`,
`oracleVersion`, `oracleHash`, `commandId`, `cwdId`, `exitCode`, `signal`,
`timedOut`, `classification`, `executed`, `fromCache`, `upToDate`, `skipped`,
`outputHash`, `outputTruncated`, `interactionId`, `actionId`,
`decisionTextHash`, `briefHash`, `claimsHash`, `diffHash`,
`reviewPackageHash`, `finalizationTurns`.

## 3. Event-by-event mapping

### 3.1 Transitions (V3)

`workflowId` is the epic `runId` for epic-scoped events and the `storyRunId` for
story-scoped events. `stepId` is a stable, safe projection identifier.

| Event | workflowId | stepId | Derived `WorkflowStatus` | Notes |
|---|---|---|---|---|
| `run_started` | `runId` | `epic-run` | `ready` | Ephemeral; not projected as a step by `projectForgeRun`, but the natural generic entry state. |
| `story_run_created` | `runId` (story) | `story-run` | `ready` | `ordinal` orders siblings. |
| `gate_started` | `runId` | `<gate>` (e.g. `G1`) | `waiting_human` | Gate status is literally `waiting_human`. |
| `human_decision_recorded` | `runId` | `<gate>` | `approved → completed`, `rejected → failed`, else `blocked` | |
| `g2_evaluated` | `runId` | `G2` | `passed → completed`, `blocked → blocked`, `failed → failed` | |
| `g2_us_evaluated` | `storyRunId` | `G2-US` | as `g2_evaluated` | `gate` absent from generic vocabulary; encoded in `stepId`. |
| `agent_execution_started` | `storyRunId` | `analysis` | `running` | ☆ |
| `agent_execution_finished` | `storyRunId` | `analysis` | `finished → completed`, `failed → failed`, else `blocked` | |
| `story_analysis_plan_validated` | `storyRunId` | `analysis-plan` | `valid → completed`, `invalid → failed`, else `blocked` | |
| `story_edit_started` | `storyRunId` | `edit` | `running` | ☆ |
| `story_edit_finished` | `storyRunId` | `edit` | `finished → completed`, `failed → failed` | |
| `story_oracles_started` | `storyRunId` | `oracles` | `running` | ☆ |
| `story_oracle_finished` | `storyRunId` | `oracle.<name>` | `passed → completed`, `failed → failed`, `blocked/skipped → blocked` | One step per oracle id. |
| `story_g3_evaluated` | `storyRunId` | `G3` | `passed → completed`, `failed → failed`, `blocked → blocked` | |

### 3.2 `workflow_evidence` (V5)

Projected `WorkflowEvidenceInput`s are validated with
`validateWorkflowEvidenceInput`; invalid candidates are kept with
`isValid: false` + `validationError` (never dropped). `source.kind` is always
`forge-ledger`.

| Event | kind | outcome | facts (whitelisted) | source extras |
|---|---|---|---|---|
| `human_decision_recorded` | `human-decision` | `approved → pass`, `rejected → fail`, else `indeterminate` | `resultCode = reasonCode`, `attempt` | `actorId`, `authorityId`, `decisionId`, `runId`, `gate` |
| `g2_evaluated` | `oracle-result` | `passed → pass`, `failed → fail`, `blocked → indeterminate` | `resultCode = code`, `attempt` | `runId`, `gate:'G2'` |
| `g2_evaluated` (`spec`) | `artifact` | — | `artifactRef = spec.path`, `artifactHash = spec.sha256` | `gate:'G2'` |
| `g2_us_evaluated` | `oracle-result` | as above | `resultCode = code`, `attempt` | `storyRunId`, `gate:'G2-US'` |
| `g2_us_evaluated` (`storySpec`) | `artifact` | — | `storySpec.path` / `storySpec.sha256` | `gate:'G2-US'` |
| `agent_execution_finished` | `agent-result` | `finished → pass`, `failed → fail` | `resultCode = status` | `runtimeId = role`, `agentId = agentName`, `caseId`, `namespaceId`, `executionId`, `runId` |
| `agent_execution_finished` (`artifact`) | `artifact` | — | `artifact.path` / `artifact.sha256` | `kind:'forge-ledger'` |
| `story_analysis_plan_validated` | `oracle-result` | `valid → pass`, `invalid → fail` | `resultCode = code` | `storyRunId` |
| `story_analysis_plan_validated` (`artifact`) | `artifact` | — | `artifact.path` / `artifact.sha256` | `kind:'forge-ledger'` |
| `story_edit_finished` | `agent-result` | `finished → pass`, `failed → fail` | `resultCode = status` | `caseId`, `editId`, `runId` |
| `story_oracle_finished` | `oracle-result` | `passed → pass`, `failed → fail`, else `indeterminate` | `resultCode = code`, `oracleId = name`, `exitCode` (int), `durationMs` (int) | `campaignId`, `editId`, `runId` |
| `story_g3_evaluated` | `oracle-result` | as above | `resultCode = status`, `attempt` | `campaignId`, `storyRunId` |
| `run_started`, `story_run_created`, `gate_started`, `*_started` | — | — | — | No evidence produced. |

### 3.3 `human_interactions` (V5)

| Event | Projection | Detail |
|---|---|---|
| `gate_started` (`gate === 'G1'`) | **open** | `kind: approval`; `workflowId = runId`; `stepId = G1`; `expectedRevision = attempt` (≥1, Forge has no workflow revision); `prompt` mentions `requiredDecision`; `actions = approve→completed`, `reject→failed`; deterministic `interactionId` = `interaction_<runId>_G1_<attempt>`. Validated by `validateHumanInteractionOpenInput`. |
| `human_decision_recorded` | **reply** | `interactionId` derived identically; `actorId` from `decision.actorId`; `outcome` = `decision.outcome`; `reasonCode` = `decision.reasonCode`; `repliedAt = at`. Only `approved`/`rejected` produce a reply. |

`requiredDecision` and `policyVersion` from `gate_started` have no generic home
and are reported as unmapped (see §4).

## 4. Fields with no place in the current `FACT_KEYS`

These Forge fields are structurally meaningful but absent from the whitelist.
The projector emits one `UnmappedForgeField` per occurrence with reason
`not_in_fact_whitelist` (field name outside the whitelist) or
`unsupported_structure` (whitelisted name carrying a nested payload). None of
them are written to any store in Step 1.

| Forge field | Seen in | Type | Why unmapped |
|---|---|---|---|
| `policyVersion` | `gate_started`, `human_decision_recorded`, `g2_evaluated`, `g2_us_evaluated`, `agent_execution_*`, `story_edit_*`, `story_oracles_*`, `story_g3_evaluated` | string | No policy-version fact key. |
| `evidenceSetHash` | `human_decision_recorded` | `sha256:…` | No evidence-set hash fact key. |
| `requiredDecision` | `gate_started` | string | Not an allowed fact; gate policy input. |
| `caseStatus` | `agent_execution_finished`, `story_edit_finished` | string | Runtime case state; not whitelisted. |
| `killedByBudget` | `agent_execution_finished`, `story_edit_finished` | boolean | No budget fact key. |
| `planSchemaVersion` | `story_analysis_plan_validated` | number | No schema-version fact key. |
| `filesModified` | `story_edit_finished` | string[] | No file-list fact key; arrays are rejected by the validator. |
| `filesCreated` | `story_edit_finished` | string[] | idem. |
| `diffValidation` | `story_edit_finished` | object | Nested object; no structured fact slot. |
| `ownerProjects` | `story_oracle_finished` | string[] | idem (array). |
| `buildHosts` | `story_oracle_finished` | string[] | idem. |
| `ownersWithTestTarget` | `story_oracle_finished` | string[] | idem. |
| `ownersWithoutTestTarget` | `story_oracle_finished` | string[] | idem. |
| `commandHash` | `story_oracle_finished` | `sha256:…`/null | No command-hash fact key (`commandId` exists but not the hash). |
| `specHash` | `story_g3_evaluated` (and `spec.sha256`/`storySpec.sha256` embed a hash) | `sha256:…` | No spec-hash fact key. |

Additional nested fields also reported when present: `spec` / `storySpec`
(object) on `g2_*`, `violations` on `g2_us_evaluated`, `analysisValidation` on
`agent_execution_finished`, `briefArtifact` on `agent_execution_started`,
`missingFiles` / `outsideFiles` on `story_analysis_plan_validated`, `target` /
`configuration` on `story_oracle_finished`.

For reference, the projector *does* consume (without flagging) structural
fields that carry identity/ordering only: `schemaVersion`, `event`, `at`,
`runId`, `storyRunId`, `parentRunId`, `runType`, `ordinal`, `gate`, `attempt`,
`status`, `outcome`, `code`, `name`, `durationMs`, `exitCode`, `workflow`,
`workItem`, `roots`, `decisionId`, `idempotencyKey`, `decision`, `executionId`,
`caseId`, `role`, `agentName`, `namespaceId`, `observedAt`, `actorId`,
`authorityId`, `editId`, `campaignId`, `artifact`.

> Note: `workflow`, `roots` and `workItem` are consumed silently today but are
> **not carried** into any generic structure — see §6 gaps.

## 5. Step 2 proposals (NOT applied)

Documented for the Step 2 decision only; nothing here is implemented.

- **Option A — extend `FACT_KEYS`.** Add domain-neutral keys such as
  `policyVersion`, `specHash`, `commandHash`, `evidenceSetHash`,
  `planSchemaVersion`, `caseStatus`, `killedByBudget`, `filesModified`,
  `filesCreated`, `ownerProjects`, `buildHosts`. Pros: reuse the existing
  evidence pipeline unchanged. Cons: the whitelist is generic, so Forge-specific
  names leak into a cross-domain contract; arrays are still rejected by the
  validator.
- **Option B — structured `facts` nesting vs flattening.** Keep flat facts but
  serialize complex values as bounded hashes/counts (e.g. `filesModified` →
  `itemCount` + a future `diffHash`), or introduce a nested `facts` shape (would
  require changing the validator, currently flat-only and bounded to 32 keys).
- **Option C — `source.*` metadata.** Carry execution context (`policyVersion`,
  `caseStatus`, `ownerProjects`, `buildHosts`, `commandHash`, `specHash`) on
  `source`, which already has an open `[key: string]: unknown` index signature
  and is not bounded by `FACT_KEYS`. Lowest-friction for non-verdict context.
- **Option D — dedicated evidence kinds.** Introduce Forge-specific kinds (e.g.
  `policy-result`, `diff-result`) rather than overloading `oracle-result`. Pros:
  honest typing. Cons: expands `WORKFLOW_EVIDENCE_KINDS`, a cross-domain change.

Recommendation to evaluate in Step 2: **C for context, B for verdict-adjacent
collections**, keeping `FACT_KEYS` minimal.

## 6. Gaps and ambiguities identified

1. **`workItem` / `roots` / `workflow` identity loss.** Consumed but not mapped:
   the generic projection loses the Story/Epic business identity
   (`workItem.id/kind`) and the resolved roots. No generic `workflowId` is
   derived from the business reference; the generic `workflowId` is the Forge
   run id. A Step 2 identity bridge is required.
2. **Start events exist but are outside the named list.** `agent_execution_started`,
   `story_edit_started`, `story_oracles_started` are real and are projected as
   `running` transitions. The brief listed only completion events; the
   `story_edit_started`↔`story_edit_finished` pairing already exists, so the
   "missing start event" gap applies only to gates (`gate_started` is the only
   start) and to `g2_*` (no `g2_started`).
3. **`attempt` vs generic `revision`.** Forge attempts are per-gate/per-campaign
   counters; the generic model tracks a single monotonic workflow revision. The
   projector reuses the Forge `attempt` as the interaction `expectedRevision`, a
   lossy heuristic that must be reconciled before any authority switch.
4. **No explicit generic workflow/revision creation.** `run_started` maps to a
   `ready` transition, but the generic instance/publication protocol expects an
   `expectedRevision: 0` creation first; Step 1 deliberately invents nothing.
5. **`waiting_human` has no reply endpoint here.** The opening interaction is
   projected, but the read-only slice never opens a real interaction nor
   drives a transition.
6. **`skipped` oracle status.** Mapped to `blocked` (no generic `skipped`).
   `ORACLE_NO_TEST_TARGET` is a non-failure skip that the G3 campaign still
   counts as complete; the individual-step mapping is therefore conservative and
   potentially misleading.
7. **Gate namespace.** Only `G1` is currently modelled as a human interaction;
   other `gate_started` gates are projected as `waiting_human` transitions with
   no interaction. Multi-gate Forge runs would need a per-gate policy.
8. **Story-scope orchestration.** Epic events use `workflowId = runId`, story
   events use `workflowId = storyRunId`; the parent/child relationship
   (`parentRunId`) is not represented in the generic model.
9. **Hash format dependency.** `g2_*` artifact evidence is only valid when
   `spec.sha256`/`storySpec.sha256` is a well-formed `sha256:<64 hex>`; malformed
   hashes surface as `isValid: false` rather than an error (by design).
