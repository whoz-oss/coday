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
All commits on top are small and self-describing (`git log --oneline 92dff5e59..HEAD`).

> **Not compiled since block B.** The last implementation sessions were told not to run build
> tools. The code was written to compile and its specs were updated, but **the first action of the
> next developer is to compile and run the suite** (§4 step 1).

### Code map (where things live)
| Concern | Class |
|---|---|
| Definition (which tools, in which order) | `workflow/Workflow.kt` (`Workflow`, `WorkflowStep(id, tool)`) |
| Definition rules (exactly 2 steps, step 2 = BatchCaseStarter) | `workflow/WorkflowValidator.kt` |
| Contract between step 1 and step 2 (`targets`) | `workflow/WorkflowTargets.kt` (`WorkflowTarget`, `WorkflowTargets.ARRAY_SCHEMA`) |
| Runtime values → step inputs (hardcoded mapping, D3) | `scheduledPrompt/ScheduledWorkflowWiring.kt` (pure function) |
| Run → scheduled prompt → workflow → `LoopConfig` | `scheduledPrompt/ScheduledPromptLoopConfigResolver.kt` |
| Resolved form executed by the loop | `workflow/LoopConfig.kt` (`LoopStep(id, tool, input, feedsNext)`) |
| Executes the resolved steps, writes the transcript | `workflow/LoopWorkflowRunnerService.kt` (knows nothing of scheduler/targets) |
| The loop agent (case ↔ runner adapter) | `agent/AgentLoop.kt` |
| Step 1, user-group workflow | `userGroup/ListAgentUsersTool.kt` (output `{ targets, members }`) |
| Step 2 | `casePlugin/BatchCaseStarterTool.kt` (input `targets`, resolves `userExternalId`) |
| Persistence / API | `workflow/WorkflowNode`, `Neo4jWorkflowRepository`, `WorkflowServiceImpl`, `WorkflowController`; SDK `api/workflow/WorkflowDto` |
| Default workflow + backfill | `workflow/DefaultWorkflowSeeder.kt` (name `Workflow.USER_GROUP_WORKFLOW_NAME = "user-group"`) |
| How a step names a tool (bare or `CONFIG__tool`) | `workflow/ToolReference.kt` (single rule, used everywhere) |
| Tools a step may use = tools granted to the loop agent | `scheduledPrompt/LoopAgentToolCatalog.kt` |
| `workflowInput` checked against the step-1 tool schema | `scheduledPrompt/WorkflowInputValidator.kt` + `workflow/JsonSchemaSubset.kt` |
| The loop agent itself | `scheduledPrompt/SchedulerLoopAgentSeeder.kt`, name `SchedulerProperties.loopAgentName` |

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
| `SchedulerScannerUnitSpec` fixtures have an execution identity (`createdBy`) | done, green — **to rework with D10 (step 9b)** |
| Case title falls back to prompt when blank | done |
| **B2** Workflow steps are `{id, tool}`; `WorkflowRunBindings` deleted; validator = exactly 2 steps | done |
| **B3** `ScheduledPrompt.workflowInput` (domain, node `workflowInputJson`, DTO, controller; must be a JSON object) | done |
| **B4** `ScheduledWorkflowWiring` + resolver + runner hand-over via `LoopStep.feedsNext`; non-object input → `Aborted`; `AgentLoop` turns unexpected errors into a `WarnEvent` | done |
| **C5** `listAgentUsers` output `{ targets: [{userExternalId}], members: [...] }` | done |
| **C6** `BatchCaseStarter` input `targets`, user resolved by external id, outcome per target | done (+ `BatchCaseStarterToolUnitSpec`) |
| **D7** stable name `user-group`; `ScheduledPromptDto.workflowName` (input only, exclusive with `workflowId`); `WorkflowService.findByName` (namespace first, then platform) | done |
| **D8** `workflowInput` validated at save against the step-1 tool `inputSchema` (subset of JSON Schema, see `JsonSchemaSubset` KDoc) | done |
| **E9 (part)** `scheduler-loop` platform LOOP agent seeded (`USER_GROUP:[listAgentUsers]`, `CASE:[BatchCaseStarter]`); name configurable | done — **routing still open**, see §4 step 9 |
| `ScheduledWorkflowWiring` injects `agentConfigId` **only** for `listAgentUsers` (strict search schemas would reject it) | done |

### Not done / known red
- **Not compiled / suite not run** since block B (see warning above).
- ⚠️ **Existing data**: the default workflow was renamed `default-audience-fan-out` → `user-group`
  *before* this branch shipped, so no environment should hold the old name. If a dev database was
  seeded with an earlier commit of this branch, delete that `Workflow` node (it is then re-seeded).
- Everything in §4 steps 9 (routing) → 22.

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

Hardcoded wiring (implemented in `ScheduledWorkflowWiring`):
- step 1 input = `ScheduledPrompt.workflowInput` (as is), plus `agentConfigId` **only when step 1
  is `listAgentUsers`** (Whoz search tools validate strictly and would reject an unknown key);
- step 2 input = `{ agentName, promptId, title, scheduledPromptId, runId }` + `targets` handed over
  from step 1 by the runner (`LoopStep.feedsNext = "targets"`).

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
- Tools concerned: `listAgentUsers` (AgentOS, **done**: also returns `members` for LLM use) and the
  Whoz `SearchTalents`, `SearchTasks`, `SearchDossiers` (Whoz side, coordinate with Vincent).

### D5. Four predefined workflows, referenced by stable name
- **AgentOS seeds one**: the user-group workflow, stable name **`user-group`**
  (`Workflow.USER_GROUP_WORKFLOW_NAME`), default of a scheduled prompt that names none.
- **Whoz creates the three others** (talent, task, dossier) via `/api/workflows` at deployment, with
  names of its choosing. AgentOS does not know them.
- Front picks among them with a shared constant (like agent templates) and sends
  **`workflowName`** on the scheduled prompt (input only, exclusive with `workflowId`); the backend
  resolves it to the namespace's workflow of that name, else the platform one. Responses carry
  `workflowId`.
- Wording/labels/metadata of inputs: hardcoded front side this sprint.

### D6. User-group workflow input
Audience = **deployment audience of the scheduled prompt's agent** (`agentConfigId`), as today. No
explicit `userGroupId`. So for that workflow `workflowInput` is empty and the wiring injects
`agentConfigId`.

### D7. Loop agent tools
The seeded platform `scheduler-loop` AgentConfig (LOOP) gets `USER_GROUP:[listAgentUsers]`,
`CASE:[BatchCaseStarter]`, and **access to the Whoz search tools through its integrations for the
namespaces that need them** (Whoz configures those integrations). Hence the validator must **not**
check step-1 tool names against a fixed catalog; a wrong name aborts the run with a readable reason.

### D8. Validation of `workflowInput` at scheduled-prompt save
On create/update: resolve the workflow, find its step-1 tool **as granted to the loop agent in the
scheduled prompt's namespace** (`LoopAgentToolCatalog`: loop agent `integrations` × effective
integration configs, resolved without a user so no credential / OAuth prompt), and check
`workflowInput` against its `inputSchema` with `JsonSchemaSubset` (type, required, properties,
`additionalProperties:false`, enum; other keywords ignored, left to the tool at run time). Keys the
wiring injects are not required. Not resolvable → 400 naming the tool, never skipped.

Consequence to know: a Whoz search tool that only lists itself with user credentials cannot be
found at save time and the save is refused. If that happens, the catalog must resolve with a
technical identity — discuss with Vincent rather than bypassing the check.

### D9. Kept from earlier work (unchanged)
`dispatchKey` idempotency (`runId:userId[:entityId]`, internal user id after resolution by external
id), `TARGETING` run status, translation at case start via stored Prompt.

### D10. Execution identity = the user having access to the loop agent (changed 2026-11-10)
The user the loop runs as (actor of the loop case's first message, `ToolContext.userId` of every
step, caller of `CaseStarterService`) is **not** the scheduled prompt's `createdBy` any more. It is
**the user who has access to the loop agent** (`scheduler-loop`) in the scheduled prompt's
namespace, i.e. its deployment audience there (`DEPLOYED_TO` user group or namespace, same query
as `listAgentUsers` / the canonical audience query of step 16).
- exactly **one** such user → the loop runs as that user;
- **zero** or **more than one** → error: the run is recorded `FAILED` with a readable reason
  ("no user / N users have access to the loop agent 'scheduler-loop' in namespace X") and
  `nextRunAt` is advanced (same treatment as the claim refusals of step 12). The prompt is **not**
  disabled: deploying the loop agent correctly fixes it. Never fall back to the creator or an admin.
- That user must hold **Namespace WRITE** (required by `CaseStarterService` to start cases for
  other users); without it the run fails with a readable reason as well.
- Consequence: the loop agent must be deployed (by the namespace admin / Whoz configuration) to
  exactly one technical user per namespace that uses scheduled prompts. Because that user has the
  agent deployed, the `@scheduler-loop` mention resolves normally — this also answers most of the
  routing problem of step 9.

## 4. Plan — do it in this order, one small commit per step

Rules: commit after every green compile; message `feat|fix|refactor|test|docs: wz-35443 - <lowercase>`
(commitlint rejects sentence case); never stage `.idea/runConfigurations/AgentOSApplication.xml`
or `factory/`; run targeted specs, full suite at the end of each block.

**Block A — get to a known state**
1. Compile, run the full `agentos-service` suite; fix residual failures (expect
   `CaseStarterServiceUnitSpec`, integration specs, anything touched by blocks B/C). Commit fixes.

**Block B — reshape the Workflow model (D2, D3)** — ✅ steps 2–4 done
2. ✅ `WorkflowStep(id, tool)`, `WorkflowRunBindings` deleted, 2-step validator, DTO/node/seeder/controller.
3. ✅ `ScheduledPrompt.workflowInput`.
4. ✅ `ScheduledWorkflowWiring` + resolver; runner hand-over via `feedsNext`.

**Block C — common contract (D4, D6)** — ✅ done
5. ✅ `listAgentUsers` → `{ targets, members }`.
6. ✅ `BatchCaseStarter` reads `targets`, resolves `userExternalId`.

**Block D — references & validation (D5, D8)** — ✅ done
7. ✅ Stable name `user-group`; `workflowName` on scheduled prompt DTO.
8. ✅ `workflowInput` validated against step-1 tool input schema at save.

**Block E — make the run actually work (review P0/P1)**
9. ✅ Seed platform `scheduler-loop` AgentConfig (`SchedulerLoopAgentSeeder`), name from
   `SchedulerProperties.loopAgentName`.
   ⏳ **Routing**: with D10 the loop runs as a user the loop agent is deployed to, so
   `CaseServiceImpl`'s agent selection (`findDeployedByNamespaceIdAndUserIdAndName`) resolves
   `@scheduler-loop` without any special entry point. Do **not** deploy the loop agent to
   everyone. Remaining check: the loop agent must not show up in @mention / agent lists of normal
   users (it only shows for its technical user and super-admins) — verify, and filter LOOP agents
   out of those lists if needed.
9b. ⏳ **Execution identity (D10) — next thing to do.** Replace the `createdBy`-based identity:
   - `SchedulerScanner.claim`: drop the `createdBy` / removed-creator / creator-WRITE guards (and
     the prompt disabling they do). Instead resolve the loop agent (`SchedulerProperties.loopAgentName`)
     and its deployment audience in `scheduledPrompt.namespaceId`; exactly one user → continue,
     zero or several → insert the run `FAILED` with the reason and advance `nextRunAt`; that user
     without Namespace WRITE → same.
   - Put the resolution behind one small interface (e.g. `LoopExecutionIdentityResolver.resolve(namespaceId): Result`
     returning the user or a refusal reason) so the scanner stays readable and it is unit-testable.
   - `ScheduledPromptExecutor.materialize(run, sp, executionUserId)`: rename `creatorId`; the loop
     case's ADMIN grant and first-message actor use that user. Decide whether the creator should
     also get READ on the loop case (to see the transcript) — default: no, keep it minimal.
   - Specs: `SchedulerScannerUnitSpec` (fixtures currently rely on `createdBy`; replace with a
     stubbed resolver: 0 / 1 / 2 users, user without WRITE), `ScheduledPromptExecutorUnitSpec`.
   - Update KDoc of `SchedulerScanner`, `ScheduledPromptExecutor`, `SchedulerLoopAgentSeeder`
     (mention that the agent must be deployed to exactly one user per namespace).
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
- ~~Production data check on `createdBy`~~: obsolete since D10 (identity no longer comes from
  `createdBy`). Replaced by: before shipping, deploy `scheduler-loop` to exactly one technical
  user (with Namespace WRITE) in every namespace that has enabled scheduled prompts, otherwise
  their runs will fail with a readable reason.
- `findEffective` of Prompt/ScheduledPrompt ignores namespace-direct deployment (behaviour question).
- Preset filters ("votre équipe" / workspace) vs search JSON: front/Whoz topic, not AgentOS.
