# Converging the ScheduledPrompt scheduler onto AgentLoop

> Decision record for ADR-64 US 4, as scoped with the Tech Lead.
> This captures **what was decided and why**, so the next conversation starts from the
> decision rather than rediscovering it.
>
> The implementation plan starts at
> [`plan-00-index-and-reconciliation.md`](./plan-00-index-and-reconciliation.md), which indexes
> the three parts and records where they contradicted each other and which reading wins.
> Read it before any of the parts.

## Scope

The scheduler must behave **identically from the end user's point of view**, but stop
computing its audience and creating cases itself. Each scheduled `Run` opens **one case
running an AgentLoop**; that loop lists the agent's audience with a user-group tool and
starts one case per user with the case-starter tool.

This is ADR-64 Option 4 delivered as **convergence without expressiveness**. The
configurable-sequence product surface — Studio, natural-language authoring, JsonPath
mapping — is deliberately out of scope. The risky half of US 4 is migrating a production
path; doing that while also exposing a new config model to users would double the blast
radius for no user-visible gain.

Unchanged: the ScheduledPrompt REST API, its DTOs, the Angular UI, recurrence / planning /
window semantics, and what an end user receives.

## Enabling facts (verified against `master`)

These made the work materially cheaper than ADR-64 assumed, and should be re-verified if
this document is read much later.

- `ScheduledPromptRun` / `ScheduledPromptUserRun` are **invisible outside the backend** —
  no REST endpoint, no OpenAPI schema, no TypeScript model, no SSE event. The execution
  ledger can be reshaped freely despite the "nothing changes externally" constraint.
- `Case.scheduledPromptId` has **zero consumers** in `libs/` or `apps/`, though it is
  present in `CaseDto` via `CaseController.toDto`.
- ADR-64 phase 1 has largely landed. `FreeCaseStarterForUserTool` (#1450) and the
  user-group tools (#1451) exist, and `LoopWorkflowRunnerService` **already** routes
  through the case-starter tool rather than `CaseLauncherService`. The end-user
  authorization gate already sits inside `CaseStarterService` — where ADR decision 10
  wants it.
- There is **no system-wide throttle on concurrent cases** today:
  `GatedRunLauncher.executionJobs` and `CaseServiceImpl.activeRuntimes` are unbounded maps,
  and `CaseLaunchGate` is a workspace-readiness gate (`Admit` / `Wait` / `Refuse`), not a
  limiter.

## Decisions

### 1. The loop creates cases directly and synchronously

ADR-64's open point offered (a) a work queue item or (b) a ledger record. **(b) is chosen.**
The executor's Phase B machinery — DB poller, `Channel`, worker pool, lease-based
`claimBatch` — is not used by the converged path.

**Per-user retry is consciously given up. The retry unit becomes the whole run.** If an
instance dies mid fan-out, the run is swept `FAILED` and unreached users were simply not
reached. No duplicates — the `(scheduledPromptId, scheduledFor)` constraint still
guarantees one run per slot — but silent partial delivery is now possible, so it must be
observable.

_Rationale._ The scheduler's five workers were throttling one path while every other path
in the system ran unbounded. Concurrency control is a case-level, system-wide concern and
belongs there, solved once. Audiences are small while the feature is in beta.

### 2. Execution identity = the creator of the ScheduledPrompt

Resolved from `EntityMetadata.createdBy`, which holds a **User UUID as a String** (written
by `AgentOsAuditorAware` via JWT → externalId → DB → `User.id`).

This is an explicit first step; a persisted `executionUserId` and eventually a per-namespace
service principal come later. The iteration is narrow: add the field, default it to
`createdBy`, change which field the scheduler reads. Nothing downstream moves.

Note this is **safer** than ADR-64 decision 11's `isAdmin = true`. That flag is a *platform
super-admin bypass* evaluated first in `PermissionServiceImpl` and short-circuiting every
subsequent check in every namespace — not namespace administration, which is an `[:ADMIN]`
edge on the `Namespace` node. Starting from the creator means the loop never sees more than
the person who configured it.

**Never fall back to a super-admin or to "any admin".** A fallback would silently escalate
privilege at exactly the moment something is already wrong. Fail the run.

Identity is resolved and validated in `SchedulerScanner.claim()`, **before the Run is
inserted** — the same treatment the scheduler already gives a missing or disabled agent
config. Three failure cases: `createdBy` is null (entities created outside an HTTP request
context get `Optional.empty()` from the auditor), the user is removed (there is no
`disabled`/`active` flag on `User`, only `metadata.removed`), or the user lacks namespace
WRITE.

That last one is a real asymmetry, not a bug: `OverlayScopeAuthorizer.authorizeCreateOrThrow`
requires namespace WRITE for a namespace-scoped prompt but only READ for a user-scoped one,
while `CaseStarterService` requires WRITE to start a case for anyone else. A user-scoped
prompt's creator can therefore only fan out to themselves. The authorization is correct and
should be allowed to refuse — what matters is that the refusal is **legible**: one clear
run-level reason, not N anonymous per-user failures.

### 3. The fallback is a default value, not a branch

The scheduler **always** hands the loop a config: `ScheduledPrompt.loopConfig` when present,
otherwise a *synthesized* default (list audience → one case per user).

A branch would leave two execution paths — the thing convergence exists to remove. A default
value leaves exactly one, makes the default a readable and testable artifact, exercises the
new data model in production from day one against a config we wrote, and gives Studio a
worked example to open.

**Do not read `AgentConfig.loopConfig` from the prompt's `agentConfigId`** — that field
identifies the *target* agent each end user talks to, not the loop agent.

### 4. The loop config model is strictly linear

An ordered list of steps. **No `forEach`, no conditionals, no branching.** Fan-out is
expressed by a *batch tool* — one step taking a list and acting on each — so the runtime
itself has no control flow at all, and the fan-out cap lives inside the tool, exactly as the
authorization gate does.

This refines ADR-64 decision 9, which reached the right conclusion (cardinality must be
readable without evaluating an expression) by a route that costs more than it needs to. The
reason to care is ADR-64's own closing risk: _"a standing risk of drifting into a workflow
engine. Every subsequent request — conditionals, branching, retries, sub-sequences — will be
individually reasonable."_ The moment `forEach` exists, "`forEach` with a filter" is a
two-line change and the argument for refusing it is only taste. A runtime that executes a
flat list is defensible on principle, indefinitely.

Step handoff is **declared** (`input.from: <stepId>`) while its **evaluation stays
hardcoded** — so the later JsonPath work replaces the evaluation without reshaping the data
model, as ADR-64 requires. A `version` field makes migration explicit. `SearchResult` /
`SearchResultItem` and the `search` / `act` special-casing are deleted; the contract between
steps becomes each tool's `outputSchema()` / `structuredOutput`.

### 5. Translation moves into the case-start

Not into the loop. The audience tools do not return `preferredLanguage`, while
`CaseStarterService` already resolves the target user and their language to build the session
context — the information exists in only one place.

`PromptService.translate(...)` is entity-bound (needs a persisted `Prompt` UUID) and there is
no API for translating arbitrary text, but the layer underneath is already text-shaped and
its Caffeine cache is keyed on `(text, sourceLanguage, targetLanguage, modelId, kind)` — not
on prompt id. So **audience size does not drive translation cost**: one prompt delivered to
400 users in three languages is a handful of cache entries.

The capability is an **opt-in** on the start request, defaulting off, so existing LLM callers
of `FreeCaseStarterForUser` keep today's behaviour and nobody's prompt is silently rewritten.
It must cover the **title** as well as the content — the title is what the user sees in their
case list.

## Cardinality: one case per entity

**Settled by the Tech Lead.** The fan-out tool creates one case **per element of the list it is
given**, and must **not** deduplicate by user. A manager reached by three incomplete profiles
receives three cases.

This makes the fan-out tool a pure map over its input and removes the question from the data
model entirely — cardinality is whatever the preceding step returned, which is readable in the
config without evaluating anything (decision 4).

It also removes the idempotency question raised in ADR-64. The `(runId, userId)` UNIQUE
constraint on `ScheduledPromptUserRun` would have silently collapsed those three cases into one,
but under decision 1 the converged path writes no `UserRun` rows at all, so the constraint never
applies. No new key is needed.

For the synthesized default there is no entity concept — the audience step returns users, so one
case per element *is* one case per user. The two readings coincide, and the tool needs no mode
flag.

## Parity traps

The ways "identical from the user's point of view" breaks silently. Each must be tested.

| # | Trap | Why it bites |
|---|------|--------------|
| 1 | **Audience** | `ListAgentUsersTool` covers only `DEPLOYED_TO → UserGroup`; `materialize()` also unions `DEPLOYED_TO → Namespace`. Unfixed, every namespace-deployed agent silently targets **nobody**. |
| 2 | **Orphan sweeper** | `findSettledRunning` is safe today only because of an invariant its own KDoc states: *"Runs with zero UserRuns never reach RUNNING status."* Convergence breaks that invariant. See [the walkthrough](#parity-trap-2-walkthrough-the-orphan-sweeper). Fixed by a `TARGETING` status. |
| 3 | **Item cap** | `agent-loop-max-items` is explicitly `50`; `materialize()` has no limit. A 200-user prompt silently stops reaching 150 people. Must fail loudly, never truncate. |
| 4 | **Translation** | `translatePromptForUser` translates content *and* title today. |
| 5 | **`scheduledPromptId`** | Written only in `createAndInjectCase` — the method this work deletes. |
| 6 | **Legible refusal** | An under-privileged creator must produce one run-level reason, not N per-user failures. |

The audience parity test asserts set equality against the `materialize()` Cypher over a
fixture covering group-deployed agent, namespace-deployed agent, a user reachable by both,
removed users, and a disabled agent. **It is the parity contract and must outlive
`materialize()` itself.**

## Parity trap 2 walkthrough — the orphan sweeper

Four links in a chain, all in code today.

**1. The sweeper runs every ~30s.** `SchedulerScanner.tickClaim()` → `processClaim()` calls
`recoverOrphanedRunningRuns(now)` on every tick, before anything else.

**2. It selects on status alone.** `ScheduledPromptRunNodeNeo4jRepository.findSettledRunning`:

```cypher
MATCH (r:ScheduledPromptRun)
WHERE r.status = 'RUNNING'
  AND NOT COALESCE(r.removed, false)
  AND NOT EXISTS {
      MATCH (ur:ScheduledPromptUserRun)
      WHERE ur.runId = r.id AND ur.status IN ['PENDING', 'RUNNING']
  }
RETURN r ORDER BY r.created
```

A run with **zero** UserRun rows satisfies `NOT EXISTS` trivially.

**3. Zero rows resolves to DONE, not FAILED.** `SchedulerScanner.recoverOrphanedRunningRuns`:

```kotlin
val finalStatus = when {
    userRunRepository.hasAnyFailed(run.id) -> RunStatus.FAILED
    else -> RunStatus.DONE
}
```

With no rows, `hasAnyFailed` is false — so the run is closed **successful**.

**4. The invariant that makes this safe today.** `ScheduledPromptExecutor.materialize`:

```kotlin
val finalStatus = if (count > 0) RunStatus.RUNNING else RunStatus.DONE
```

A run only becomes `RUNNING` when `count > 0`, i.e. when UserRun rows already exist, written in
the same `@Transactional`. `findSettledRunning`'s KDoc states this explicitly: *"Runs with zero
UserRuns never reach RUNNING status (they are transitioned directly to DONE in
`ScheduledPromptExecutor.materialize`)."*

**What convergence changes.** Under decision 1 the converged `materialize` creates no UserRun
rows — it creates one loop case. `count` is structurally zero. Leave the code as-is and the run
goes straight to `DONE` before the loop has started; set it to `RUNNING` instead and the sweeper
closes it `DONE` on the next tick. Either way: **reported successful, delivered nothing, no error
anywhere.**

This is not a race — there is no timing window to lose. It happens on every run.

**The fix.** `TARGETING`, a status the sweeper's `WHERE r.status = 'RUNNING'` does not match. The
converged path goes `CLAIMED → TARGETING → DONE | FAILED`, never entering `RUNNING`, so no window
exists in which the sweeper can see it. `findSettledRunning` stays untouched and keeps serving
legacy rows. Two consequences:

- `existsActiveByScheduledPromptId` (the overlap guard) selects
  `status IN ['CLAIMED', 'RUNNING']` and **must** gain `'TARGETING'`, or a second tick will claim
  a new run while the loop case is still fanning out.
- `findSettledRunning`'s KDoc must say that `TARGETING` is excluded *by design*. Without that
  note the bug is one plausible edit away.

## The `createdBy` question — what is and is not verified

**Verified in code.** Auditing is correctly wired for `ScheduledPrompt`:
`Neo4jPersistenceConfiguration` carries `@EnableNeo4jAuditing` and lists
`io.whozoss.agentos.scheduledPrompt` in `basePackages`; `ScheduledPromptNode` declares
`@CreatedBy val createdBy: String? = null`; and `toDomain()` / `fromDomain()` map it to and from
`EntityMetadata.createdBy`. A prompt created through `POST /api/scheduled-prompts` gets the
creator's **User UUID**.

**Not verified.** Whether existing production rows actually carry it. Three ways `createdBy` ends
up null, all visible in `AgentOsAuditorAware`:

```kotlin
override fun getCurrentAuditor(): Optional<String> =
    runCatching {
        val externalId = securityService.resolveCurrentIdentity()
        userRepository.findByExternalId(externalId)?.id?.toString()
    }.getOrNull()
        ?.let { Optional.of(it) }
        ?: Optional.empty()
```

1. **No HTTP request context** — `resolveCurrentIdentity()` reads `RequestContextHolder`, so a
   prompt created by a migration, a seeder or a background job gets `Optional.empty()`.
2. **Identity resolves to no User row** — `findByExternalId` returns null, `runCatching` succeeds
   *with* null, and the elvis yields `Optional.empty()`. A silent null, not an error.
3. **Rows predating the annotation** — if `@CreatedBy` was added to `ScheduledPromptNode` after
   the first prompts were written, those rows have no such property.

The bean's own KDoc still claims *"Currently only `AgentConfigNode` uses auditing annotations"* —
stale, since `ScheduledPromptNode` now does too, but a hint that scheduled prompts were not in
mind when the null-tolerance was designed.

**Why it matters only now.** Nothing reads `createdBy` today — `ScheduledPromptController.toDto`
passes it to the DTO and no client consumes it. Decision 2 promotes it from a display field to
the **execution identity**, so a null that is currently invisible becomes a prompt that stops
firing.

**Resolve it with one query**, before the identity guard ships:

```cypher
MATCH (sp:ScheduledPrompt)
WHERE NOT COALESCE(sp.removed, false) AND sp.enabled = true
RETURN count(*) AS total,
       count(sp.createdBy) AS withCreator,
       count(*) - count(sp.createdBy) AS withoutCreator
```

If `withoutCreator` is 0, the guard is purely defensive and this is a non-issue. If not, those
prompts need a creator backfilled before the guard is enabled — otherwise enabling it switches
them off.

## Declared non-goals

Recorded so they are decisions rather than surprises.

- **Per-user retry** — see decision 1.
- **A loop-delivered case starting a further case.** `CaseStarterService.isInStartedCase`
  walks the `parentCaseId` chain and refuses when any ancestor has a non-null
  `originCaseId`. The loop case itself passes, but every case it delivers has `originCaseId`
  set — so convergence spends the one available level of start depth. "loop → case → loop"
  is structurally blocked.
- **Renaming `Run` / `UserRun`** to general execution-ledger names. Renaming a persisted
  Neo4j label during a behavioural migration doubles risk for no functional gain. Do it as a
  chore once convergence is stable.
- Natural-language loop-config authoring; JsonPath step-to-step mapping; Studio UI.

## Cheaper than ADR-64 expected

The Agent-contract change (ADR-64: _"a breaking SDK change… requires a migration note for
plugin authors"_) is nearly free. There are exactly **three** `Agent` implementations —
`AgentSimple`, `AgentAdvanced`, `AgentLoop` — all inside `agentos-service`, and **no module
outside it implements `Agent`**. The plugins implement `ToolPlugin` / `StandardTool`, which
are untouched. `AgentLoop` already emits its events with `llmProvider = null`. Making the
two interface fields nullable has zero real-world consumers.

## Open, for the next iteration

- **Execution identity**: move from `createdBy` to a persisted `executionUserId`, then to a
  per-namespace service principal (a `User` with `isAdmin = false` holding an `[:ADMIN]` edge
  on exactly one namespace, plus a `kind: HUMAN | SERVICE` discriminator so it is excluded
  from member lists, cannot authenticate, and is not counted by the anti-lockout guard in
  `updateMembers`).
- **One case per user, or one per entity?** With discovered targets a user may be reached by
  several entities — a manager with three incomplete profiles. The existing `(runId, userId)`
  constraint collapses that into one case; `(run, entity, user)` would make three. One case
  listing all their talents is probably what the product wants and three unsolicited cases
  probably is not — but this is the Product Owner's call, and it should be answered
  explicitly rather than decided silently by a uniqueness constraint.
- **Interactive triggering of a loop config** has no `Run` to attach to. Making the batch
  step refuse without a run context — reporting what it *would* have done — turns that into
  a dry run, which is honest about the asymmetry and hands Studio its preview for free.
