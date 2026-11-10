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

> **Not compiled since block B/C.** The last implementation session was told not to run build
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
| Default workflow + backfill | `workflow/DefaultWorkflowSeeder.kt` |

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
| **B2** Workflow steps are `{id, tool}`; `WorkflowRunBindings` deleted; validator = exactly 2 steps | done |
| **B3** `ScheduledPrompt.workflowInput` (domain, node `workflowInputJson`, DTO, controller; must be a JSON object) | done |
| **B4** `ScheduledWorkflowWiring` + resolver + runner hand-over via `LoopStep.feedsNext`; non-object input → `Aborted`; `AgentLoop` turns unexpected errors into a `WarnEvent` | done |
| **C5** `listAgentUsers` output `{ targets: [{userExternalId}], members: [...] }` | done |
| **C6** `BatchCaseStarter` input `targets`, user resolved by external id, outcome per target | done (+ `BatchCaseStarterToolUnitSpec`) |

### Not done / known red
- **Not compiled / suite not run** since block B/C (see warning above).
- The only TODO left in code: `ScheduledPromptServiceImpl.resolveWorkflowId` — validation of
  `workflowInput` against the step-1 tool schema (§4 step 8).
- Everything in §4 steps 7 → 22.

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
- step 1 input = `ScheduledPrompt.workflowInput` (as is), plus `agentConfigId` (always added; a
  `workflowInput` key of the same name wins; search tools that do not declare it ignore it);
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
The seeded platform `scheduler-loop` AgentConfig (LOOP) gets `USER_GROUP:[listAgentUsers]`,
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
`dispatchKey` idempotency (`runId:userId[:entityId]`, internal user id after resolution by external
id), `TARGETING` run status, translation at case start via stored Prompt, execution identity =
scheduled prompt creator (never fall back to an admin).

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
