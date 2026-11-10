# WZ-35443 / PR #1475 — Handover: scheduler → AgentLoop convergence + Workflow entity

> **Read this first.** It supersedes the plan documents in this folder where they disagree
> (`plan-00` … `plan-part3`, `agentos-scheduler-agentloop-convergence.md`). Those remain useful as
> background on *why* (parity traps, TARGETING, dispatchKey), not as the current plan.
>
> Author of the decisions: Thomas Martin (owner), with Vincent Couturier and Mathieu De Armey
> (meeting of 2026-11-10). Owner is on holiday: decisions below are final unless they conflict
> with the code, in which case raise it with Vincent.

## 1. Goal in one paragraph

A `ScheduledPrompt` no longer computes its audience and creates cases itself. Each run opens
**one loop case** (kept on purpose: it is the observable transcript of the run) executed by the
`AgentLoop` (zero-LLM). The loop runs a **Workflow** — a reusable definition of *which tools, in
which order* — fed at runtime with the scheduled prompt's parameters. Step 1 finds targets, step 2
(`BatchCaseStarter`) starts one case per target. User-visible behaviour of the historical
scheduler (agent audience → one case per user, translated prompt) must stay identical.

## 2. Status of the branch (as of handover)

Branch `feature/archay/adr64-us4-scheduler-agentloop-convergence`, master merged in (`92dff5e59`).
22 commits on top, all small and self-describing (`git log --oneline 92dff5e59..HEAD`).

### Done
| Area | State |
|---|---|
| `Workflow` entity: domain, `WorkflowNode`, repo, Neo4j wiring, schema (`doubleKey` unique on `(namespaceId, name-slug)`, tombstone on soft-delete) | done |
| `WorkflowService` (+ unit spec): name uniqueness per scope, default undeletable/unrenamable, 409 when referenced by a scheduled prompt, `resolveForScheduledPrompt` (default / platform / same-namespace, 400 otherwise) | done |
| `WorkflowController` `/api/workflows` full CRUD, permissions: platform = read for all, write Super Admin; namespace = READ / WRITE on namespace; unreadable → 404 | done, **no controller test yet** |
| SDK DTOs `WorkflowDto` / `WorkflowStepDto`, `ScheduledPromptDto.workflowId` (optional, default workflow when absent) | done, **OpenAPI not regenerated** |
| `ScheduledPrompt.loopConfig` → `workflowId`; startup seeder creates default workflow + backfills `workflowId` | done |
| `ScheduledPromptLoopConfigResolver` resolves run → scheduled prompt → workflow; synthesizer deleted | done, **will be reshaped (§4 step 2)** |
| App starts without a `LoopConfigResolver` (scheduler disabled): `AgentServiceImpl` takes it nullable | done |
| `BatchCaseStarter` only granted when explicitly named (`RequiresExplicitGrant` + `ToolResolverService`), agent allowlist applied, invalid UUIDs → `INVALID_INPUT`, input schema aligned | done |
| `SchedulerScannerUnitSpec` fixtures have an execution identity (`createdBy`) | done, green |
| Case title falls back to prompt when blank | done |

### Not done / known red
- **Full test suite not confirmed green** since the last 3 commits. Before them: 159 failures, the
  bulk being one root cause (missing `LoopConfigResolver` bean) now fixed. **First action: run the
  full suite** (`nx run agentos-service:test`, read `build/test-results/test/*.xml`).
- The current Workflow model still has **per-step `params` with `{{run.*}}` bindings**
  (`WorkflowRunBindings`). The meeting **rejected this** — see §3. Step 2 of the plan removes it.
- Everything in §4 steps 2 → 10.

## 3. Design decisions (final)

### D1. Keep the loop agent and the loop case
The loop case is the run's transcript (tool request/response events per step). Do **not** replace
it with a backend executor. Manually launched LOOP agents are **not** a requirement (UI
`agent-config-launch` LOOP flow and `loopConfig` references in `libs/agentos-ui` /
`libs/agentos-api-client` can be removed).

### D2. A Workflow = list of steps, no parameters
`Workflow { namespaceId?, name, description?, version=1, steps: [ { id, tool } ] }`.
Parameters are **runtime**, never in the definition:
- **constants of the scheduled prompt**: agent, prompt (`promptTemplateId`), title, and the new
  `ScheduledPrompt.workflowInput` (JSON, e.g. search criteria);
- **run values**: `runId`, `scheduledPromptId`;
- **step outputs**: the targets produced by step 1.

### D3. Exactly two steps, enforced at validation
Step 1 = a *target search* tool; step 2 = `BatchCaseStarter` (bare or `CONFIG__BatchCaseStarter`).
The data mapping between steps is **hardcoded** this sprint (no JsonPath / FEEL / expression
language). Later evolution (context-based data flow, expression language, planning table) is
explicitly out of scope; keep `steps` a list so the model does not need reshaping.

Hardcoded wiring (to implement in one class, e.g. `ScheduledWorkflowWiring`):
- step 1 input = `ScheduledPrompt.workflowInput` (as is), plus `agentConfigId` for the user-group
  workflow (see D6);
- step 2 input = `{ agentName, promptId, title, scheduledPromptId, runId, targets: <step1.targets> }`.

### D4. Common output contract for all target-search tools
Every step-1 tool returns in its structured output:
```json
{ "targets": [ { "userExternalId": "…", "entityId": "…", "entityType": "TALENT" } ] }
```
- identifier = **user `externalId`** (decision). `BatchCaseStarter` resolves it via
  `UserService.findByExternalId` (unknown → counted `unknownUser`).
- `entityId`/`entityType` optional: present for Whoz searches (→ `activeContext` session context),
  absent for user groups.
- One case **per element**, no dedup (unchanged).
- Tools concerned: `ListAgentUsers` (AgentOS, must be adapted) and the Whoz `SearchTalents`,
  `SearchTasks`, `SearchDossiers` (Whoz side, coordinate with Vincent).

### D5. Four predefined workflows, referenced by stable name
- **AgentOS seeds one**: the user-group workflow (current default, `default-audience-fan-out`;
  consider renaming to a stable public name such as `user-group` — it is the reference the front
  uses).
- **Whoz creates the three others** (talent, task, dossier) via config/API at deployment. AgentOS
  does not know them.
- Front picks among them with a shared constant (like agent templates). The DTO should accept the
  workflow **by name** as well as by id (to decide: `workflowName` field, or resolve-by-name endpoint).
- Wording/labels/metadata of inputs: hardcoded front side this sprint.

### D6. User-group workflow input
Audience = **deployment audience of the scheduled prompt's agent** (`agentConfigId`), as today. No
explicit `userGroupId`. So for that workflow `workflowInput` is empty and the wiring injects
`agentConfigId`.

### D7. Loop agent tools
The seeded platform `scheduler-loop` AgentConfig (LOOP) gets `USER_GROUP:[ListAgentUsers]`,
`CASE:[BatchCaseStarter]`, and **access to the Whoz search tools through its integrations for the
namespaces that need them** (Whoz configures those integrations). Hence the validator must **not**
check step-1 tool names against a fixed catalog; a wrong name aborts the run with a readable reason.

### D8. Validation of `workflowInput` at scheduled-prompt save
On create/update: load the workflow, take step 1's tool `inputSchema`, validate `workflowInput`
against it (400 with readable errors). This is the "backend must validate the input" requirement.
Open technical point: the tool is an integration resolved per namespace/user — resolve it with the
same machinery as a run (`ToolResolverService` + effective integration configs of the namespace for
the loop agent). If not resolvable at save time, fail with a clear 400 rather than skipping.

### D9. Kept from earlier work (unchanged)
`dispatchKey` idempotency (`runId:userExternalId[:entityId]` — adapt key to external id or keep
internal id after resolution, internal is fine), `TARGETING` run status, translation at case start
via stored Prompt, execution identity = scheduled prompt creator (never fall back to an admin).

## 4. Plan — do it in this order, one small commit per step

Rules: commit after every green compile; message `feat|fix|refactor|test|docs: wz-35443 - <lowercase>`
(commitlint rejects sentence case); never stage `.idea/runConfigurations/AgentOSApplication.xml`
or `factory/`; run targeted specs, full suite at the end of each block.

**Block A — get to a known state**
1. Run the full `agentos-service` suite; fix residual failures (expect `CaseStarterServiceUnitSpec`,
   integration specs). Commit fixes.

**Block B — reshape the Workflow model (D2, D3)**
2. `WorkflowStep(id, tool)`; delete `WorkflowRunBindings` + spec; `WorkflowValidator` = exactly 2
   steps, step 2 is `BatchCaseStarter`, step 1 is not, distinct non-blank ids/tools; update
   `WorkflowDto/StepDto` (drop `params`, `from`), node JSON, seeder, controller mappers, specs.
   `LoopConfig`/`LoopStep` stay the runner's resolved form.
3. `ScheduledPrompt.workflowInput: JsonNode?` (domain, node `workflowInputJson`, DTO, controller).
4. `ScheduledWorkflowWiring` + rewrite `ScheduledPromptLoopConfigResolver` to build the 2-step
   `LoopConfig` (D3); runner: step 2 receives `targets` directly (drop `previousStepOutput`).
   Make non-object params an `Aborted`, not a `ClassCastException`.

**Block C — common contract (D4, D6)**
5. `ListAgentUsers` structured output → `{ targets: [{ userExternalId }] }` (check other callers of
   `MEMBER_ARRAY_SCHEMA`; keep the member list for LLM use if needed via a separate field).
6. `BatchCaseStarter` reads `targets`, resolves `userExternalId` → user; update schema & tests.

**Block D — references & validation (D5, D8)**
7. Stable names: rename/seed user-group workflow; accept workflow by name on scheduled prompt DTO.
8. Validate `workflowInput` against step-1 tool input schema at save.

**Block E — make the run actually work (review P0/P1)**
9. Seed platform `scheduler-loop` AgentConfig (CaseIntegrationConfigSeeder pattern), name from
   `SchedulerProperties`. **Investigate routing**: the executor posts `@scheduler-loop` as the
   creator; a platform agent without `DEPLOYED_TO` is only visible to super-admins → add a narrow
   internal entry point to start a turn with a given agentConfigId (not reachable from REST/LLM);
   ensure it does not appear in @mention/agent lists.
10. Executor: drop the inert `@Transactional` self-call; explicit compensation (soft-delete loop
    case, run FAILED with reason) on failure after case creation; fix class KDoc.
11. `recoverOrphanedTargetingRuns` authoritative & restart-safe: read loop case status each tick
    (IDLE→DONE, ERROR/KILLED/missing→FAILED), only time out (`targetingOrphanThresholdMinutes`,
    remove hardcoded 30 min) when still running.
12. Claim refusals (creator lacks WRITE, platform scope) → insert run FAILED with reason **and**
    advance `nextRunAt`.
13. `agent-loop-max-items: 500`, KDoc "over cap fails loudly, nothing started".
14. `deleteByParent` tombstones `dispatchKey`.

**Block F — queries cleanup (agreed: same PR)** — from the audit in the conversation:
15. Delete dead `ScheduledPromptUserRun` surface (keep `hasAnyFailed` while legacy sweep exists),
    after porting its traversal tests to `findUsersByAgentId` (= audience parity test).
16. One canonical audience query (`findAudience(agentConfigId, namespaceId, enabledOnly)`).
17. Merge `findConcerningUser*`; delete `findActiveByParentCaseId`; `linkParentToChild` →
    child-link service; merge favorite set/clear; member projection helper; pass case ids to
    `findDirectRelations`; fix `querySearchResults` cartesian product; `countAncestorDepth` once.
18. Bugs: disabled agents survive namespace delete; `"null"` namespace string; inert
    `@Transactional` on inline `Neo4jAgentConfigRepository`; `listDirectRelations` swallows
    errors; `findGroupsByUserExternalIds` missing DISTINCT.
19. Standardize `NOT COALESCE(x.removed,false)`, `@Transactional(readOnly=true)` on reads.

**Block G — finish**
20. Tests: WorkflowController permissions, `workflowInput` validation, resolver wiring,
    `BatchCaseStarter` (targets, cap, dispatchKey, skipped), `CaseStarterService` (translation,
    dispatchKey race), executor compensation, targeting sweep, rewrite
    `ScheduledPromptBatchScenarioSpec` end-to-end.
21. Remove `loopConfig` from `libs/agentos-ui` + regenerate `libs/agentos-api-client`; regenerate
    OpenAPI (`nx run agentos-service:generate-openapi-spec`, then `:regenerate`).
22. Docs: delete or mark obsolete `docs/study/plan-*` & convergence doc; update
    `agentos/docs/architecture.md` (CaseWorker, Workflow). PR title → conventional
    (`feat: #1475 …` / WZ-35443).

## 5. Open points (not blocking, decide with Vincent)
- Whoz search tools must emit `targets` (D4) — Whoz-side change, track separately.
- Production data check before shipping: enabled `ScheduledPrompt` without `createdBy` would be
  disabled by the identity guard (query in `agentos-scheduler-agentloop-convergence.md`).
- `findEffective` of Prompt/ScheduledPrompt ignores namespace-direct deployment (behaviour question).
- Preset filters ("votre équipe" / workspace) vs search JSON: front/Whoz topic, not AgentOS.
