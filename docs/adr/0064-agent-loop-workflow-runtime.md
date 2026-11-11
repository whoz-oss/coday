# ADR-0064 — Converge scheduled execution onto AgentLoop as the single execution primitive

## Status

**PROPOSED : 2026-10-13**

<!--
ACCEPTED : {YYYY-MM-DD}
REJECTED : {YYYY-MM-DD}
DEPRECATED : {YYYY-MM-DD}
SUPERSEDED : {YYYY-MM-DD}
→ {decision summary}
-->

> **Open point blocking US 4**: the role of `UserRun` after convergence — whether it remains a work
> queue item or becomes a ledger record. See [Open point — the fate of `UserRun`](#open-point--the-fate-of-userrun).
> The execution identity, previously open, is now settled (decision 11).

## Discussion submitters

| Name             | Role          |
|------------------|---------------|
| Thomas Martin    | Developer     |
| Frédéric Delsert | Developer     |
| Mathieu De Armey | Developer     |
|                  | Product Owner |
|                  | Architect     |
|                  |               |

## Functional requirement overview

AgentOS must run **deterministic, scalable, repeatable business automations** — sequences of operations
decided by configuration, not improvised by a language model.

The canonical case: *"find the talents whose profile is incomplete, and open a conversation with each of
their managers so they can review it."* This is not a conversation. It is a search, a resolution of who
is concerned, and a fan-out of one case per person. There is no judgement to delegate to an LLM, and
every run must behave identically.

Two subsystems currently address neighbouring halves of this need:

- **`ScheduledPrompt`** — mature delivery machinery: *"send this prompt to these users on this cadence"*.
  Persisted Run/UserRun, lease-based claim, worker pool, backoff, execution windows, orphan recovery.
  It cannot compute *who* is concerned — the target list must be known up front.
- **`AgentLoop`** (V0, shipped 3.30.0, `#WZ-35090`, PR #1414) — computes who is concerned and fans out,
  but fire-and-forget: no persistence, no retry, no resume, one hardcoded SEARCH → ACT shape.

This ADR covers unifying the two and generalising the loop into a configurable sequence of tools.

Full documentation:

- `docs/study/agentos-agent-loop.md` — V0 code analysis, invariants, debt register
- `docs/study/agentos-agent-loop-v1.md` — target architecture, open decisions, story detail

## Context

The need comes from the product side: recurring, user-facing automations over a population (talents,
managers, teams) that must run on a schedule, reach the right people, and be explainable when they
misfire.

Forces at play, stated neutrally:

- **Determinism vs. flexibility.** The business asks for repeatable outcomes. An LLM-orchestrated agent
  can express any workflow but guarantees none. A hardcoded workflow guarantees the outcome but
  expresses exactly one thing.
- **Two subsystems, one concern.** Both resolve target users, check agent access, resolve session
  context and create a case. Two implementations of one invariant is how the interactive, scheduled and
  loop paths stop being symmetric.
- **Asymmetric maturity.** `ScheduledPrompt` has the reliability apparatus; `AgentLoop` has the
  computation. Neither alone covers the requirement.
- **Time.** V0 was delivered as a proof of concept under sprint pressure, explicitly flagged
  experimental and outside the stability contract covering `SIMPLE` / `ADVANCED`. Deliberate debt was
  accepted in exchange for shipping.
- **Vendor coupling.** V0's search result types mirror the Whoz Search tool response schema — the code
  says so in its own KDoc. Acceptable for one caller, blocking for any other.
- **Scaling.** The target is multi-instance execution with load smoothing, which requires the process
  definition to be separable from the runtime context feeding it.
- **Blast radius.** A loop creates real cases for real users. A V0 run is capped at 50 items. Any
  generalisation must carry that guarantee forward rather than lose it by accident.
- **Scheduled execution has no human trigger.** This breaks V0's first authorization gate, which assumed
  a person clicked a button.
- **End users receive cases they did not ask for.** A case appearing unannounced in someone's list, from
  an agent, about their own data, is a trust question as much as a technical one.

## Technical considerations

### AgentLoop V0 as it stands

`ExecutionMode.LOOP` is a third execution mode alongside `SIMPLE` and `ADVANCED`. An `AgentLoop` is a **zero-LLM
agent**: it implements the `Agent` SDK contract, declares `llmProvider = "none"` /
`llmModel = "agent-loop"` as sentinels, and runs a fixed sequence:

1. **SEARCH** — invoke one configured tool, **under the triggering user's identity**, with opaque params.
2. **ACT** — for each returned entity: resolve the target AgentOS user, verify that user's access to the
   target agent, resolve session context, create a case on their behalf, seed it with
   `"@agentName <task>"`.

Component decomposition is sound and worth preserving: `AgentLoop` is a pure adapter;
`LoopWorkflowRunnerService` owns the workflow and knows nothing about how it was triggered;
`CaseLauncherService` is a narrow `fun interface` carried through the execution context to avoid a
`CaseService → AgentService → CaseService` cycle.

### Invariants established by V0 (must survive the change)

1. **Two independent authorization gates.** The trigger user authorizes the search — it runs with their
   identity and data visibility. Each resolved end-user must *independently* hold access to the target
   agent before a case is created on their behalf.
2. **Symmetry across the three paths.** Interactive `@mention`, `ScheduledPromptExecutor` and the loop
   enforce the same access check. A user only receives an agent-driven case if they could have invoked
   that agent manually.
3. **Bounded blast radius.** `agentLoopMaxItems` (50) caps the fan-out; overflow is **reported, not
   silently dropped**.
4. **Layered failure granularity.** Run-invalidating failures abort with a readable reason; per-entity
   failures are counted and never stop the loop.
5. **Entity context travels as data**, via `activeContext` in `sessionContext` — not by string
   interpolation into the prompt.

### Known V0 debt

| id | debt                                                                                                |
|----|-----------------------------------------------------------------------------------------------------|
| D1 | Search result types mirror the Whoz schema — blocks any other tool from driving a loop              |
| D2 | `loopConfig` is an opaque `JsonNode` validated only at run time                                     |
| D3 | `AgentConfig.loopConfig` KDoc documents a message-override behaviour the code has never implemented |
| D4 | No run persistence: no status, no idempotency, no resume; a crash mid-fan-out is invisible          |
| D5 | Only the first search page is processed                                                             |
| D6 | Case launching is duplicated with `ScheduledPromptExecutor.createAndInjectCase`                     |
| D7 | Single trigger surface: a dedicated UI launch page                                                  |

### ScheduledPrompt as it stands

Persisted `ScheduledPrompt` → `Run` → `UserRun` hierarchy. A run materialises one `UserRun` per target
user via Cypher INSERT-SELECT **from a target list known before execution starts**; workers claim
batches under a lease (at-least-once delivery), execute within configured windows, back off on failure,
and recover orphans. Terminal status per user.

`ScheduledPromptExecutor.createAndInjectCase` performs the same access check as the loop and already
shares `UserSessionContextResolver` with it. `Case.scheduledPromptId` already records which scheduler
initiated a case (`wz-34012`) — a precedent this ADR reuses twice.

## Considered options

### Option 1 — Harden V0 in place

Keep the hardcoded SEARCH → ACT shape and pay the debts individually: lift the search result types into
the SDK, add a `LoopRun` entity for observability, validate `loopConfig` at write time, share
`CaseLauncherService` with the scheduler.

*Assessment.* Lowest risk, each step independently valuable. But it generalises the wrong thing: it
makes *one workflow shape* reusable rather than making *workflows* expressible. A second business
sequence still requires new code in the runner. It also builds a `LoopRun` aggregate duplicating what
the case event log already provides, and leaves two execution subsystems in parallel. **Rejected.**

### Option 2 — Express the sequence as an LLM-orchestrated agent

Use `ExecutionMode.ADVANCED` and let the model decide the tool sequence from a prompt.

*Assessment.* Maximum flexibility, zero determinism. **Rejected** on the core requirement: these
automations touch real users at scale and must behave identically on every run. A model deciding
whether to call `createCase` 3 or 300 times is not an acceptable blast-radius model.

### Option 3 — Generalise AgentLoop, leave ScheduledPrompt untouched

Make the loop a runtime over a declared sequence of tools, with business operations (`createCase`,
`Targetter`, `UserGroupResolver`) expressed as tools and initiation as a layer above. `ScheduledPrompt`
keeps creating cases itself and is not impacted; the two subsystems share only the `createCase` tool.

*Assessment.* Resolves the expressiveness and coupling debts (D1, D7) but **not the reliability debt (D4, D5)**, and
only half of D6. The loop would stay fire-and-forget while the mature
retry/lease/recovery machinery sits next to it, unused. Two execution paths remain, and the symmetry
invariant must keep being maintained by hand in both. **Rejected as insufficient.**

### Option 4 — Merge AgentLoop into the ScheduledPrompt execution machinery

The scheduler stops creating cases itself. Every scheduled execution runs **through** an AgentLoop: the
scheduler triggers a case, that case runs the loop, and the loop performs targeting and case creation as
tool steps. The loop inherits the scheduler's persistence, claim, retry, backoff and recovery; the
scheduler inherits the loop's ability to compute who is concerned.

*Assessment.* Resolves expressiveness, coupling *and* reliability. One execution path for both triggers.
Costs a data-model redesign and a migration of a production path. **Selected.**

## Decision

**We will merge AgentLoop into the ScheduledPrompt execution machinery: the scheduler will always
execute through an AgentLoop instead of creating cases itself (Option 4), and AgentLoop will be
generalised into a runtime executing a declared sequence of tools.**

```
  ScheduledPrompt ---- triggers ----► Case ---- runs ----► AgentLoop
  (cadence, windows,                 (ledger,             (ordered tool sequence)
   persistence, claim,                transcript,             SearchTool        → Whoz
   retry, recovery)                   kill path)              Targetter
                                                              UserGroupResolver → AgentOS DB
                                                              createCase        → Case per user
```

Concretely:

1. **We will replace `scheduler.run` with a case running an AgentLoop.** The scheduler stops owning case
   creation, user fan-out and prompt delivery; it becomes a trigger that supplies cadence, runtime
   context and inputs. There is one execution path, used by the interactive and scheduled triggers alike.

2. **We will separate process from context.** The calling layer initiates a run and supplies runtime
   context, inputs and parameters. The loop defines only the sequence of operations. No business logic
   in the scheduler; no runtime context hardcoded in the loop.

3. **We will express business operations as tools.** `createCase`, `Targetter` and `UserGroupResolver`
   join the existing search tool as ordinary, grantable tools. The runtime knows nothing about cases,
   talents or user groups.

4. **We will redesign the agent config data model** to carry an ordered sequence of tool steps with
   declared data handoff, replacing the single opaque SEARCH/ACT payload.

5. **We will delete the Whoz-shaped search result types rather than generalise them.** Tool
   `outputSchema()` / `structuredOutput` becomes the contract between steps.

6. **We will make the case transcript the step-level run ledger.** Each step is emitted as a
   `ToolRequestEvent` / `ToolResponseEvent` in the case. We will not introduce a `LoopRun` entity — the
   scheduler's `Run` provides run-level persistence and the case provides step-level detail.

7. **We will differentiate the loop from the LLM `Agent` contract**, removing the `llmProvider = "none"`
   sentinel, while **keeping `ExecutionMode` as the entry point** — that is what grants `@mention`
   routing, the deployment/permission graph, tool resolution with credentials and case event streaming.

8. **We will declare fan-out explicitly** (e.g. a step declaring `forEach`), not derive it implicitly
   from the shape of mapped data. Cardinality is the blast radius and must be readable in the config
   without evaluating an expression. Accordingly, **the item cap becomes a runtime cap on total tool
   invocations per run**, replacing `agentLoopMaxItems` as the guarantee.

9. **We will place the end-user authorization gate inside the `createCase` tool.** The tool re-verifies
   that the target user holds access to the target agent and refuses otherwise. The runtime must never
   be the component that *remembers* to check.

10. **We will implement search pagination**, which convergence makes safe: the scheduler's persistence
    and claim mechanics give a multi-page run the resumability it lacked in V0.

11. **We will model execution identity and accountability as two distinct identities.**
    - **Execution identity**: a namespace administrator (`isAdmin = true`) runs the AgentLoop. This
      bounds what the search can see.
    - **Accountability identity**: the creator of the scheduled prompt is recorded as accountable for
      the automation. It is never used for data scoping.
    - **Target identity**: each created case belongs to its targeted end user, as today.

12. **We will tell end users that a case was initiated automatically, and on whose behalf**, resolving
    the accountable user through the scheduler id already carried on the case (`Case.scheduledPromptId`).

13. **We will enforce cancellation discipline on every step.** Each per-step error wrapper catches and
    rethrows `CancellationException` before any generic handler, as `LoopWorkflowRunnerService.search`
    already does. This matters more under convergence: a swallowed cancellation now interacts with lease
    expiry and may cause a reclaimed run to execute twice.

### Deferred to a later sprint

- **Natural-language generation of loop configs.** Requires write-time `loopConfig` validation as a
  prerequisite — an LLM authoring a fan-out that creates N cases for N real users must never
  generate-and-run; a human confirms a validated config.
- **Expression language (JsonPath) for data mapping between steps.** Until then, step-to-step mapping is
  hardcoded but **declared explicitly in the config shape**, so the later change replaces the *evaluation* of the
  mapping without reshaping the data model.

## Open point — the fate of `UserRun`

This must be settled in US 4. It is the one place where convergence can quietly *lose* the reliability
it is meant to gain.

**The sequencing inversion.** Today `UserRun` rows are materialised **before** execution, by Cypher
INSERT-SELECT over a target list that is already known. After convergence, targets are **discovered at
run time** by the search and targeting steps. You cannot INSERT-SELECT from a query you have not run
yet, so the materialisation point necessarily moves into the loop.

**Two roles are being conflated**, and "the `createCase` tool should use `UserRun`" is ambiguous between
them:

| role                    | what `createCase` does                                                            | what is preserved                                                                                                      | what is lost                                                                                                                  |
|-------------------------|-----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| **(a) Work queue item** | creates a `UserRun` in `PENDING`; the existing worker pool claims and delivers it | lease-based claim, per-user retry, backoff, orphan recovery, per-user terminal status, idempotency, **load smoothing** | the loop no longer knows the launched case ids synchronously; its census reports *queued*, not *created*                      |
| **(b) Ledger record**   | creates the case directly, then records a `UserRun` as an audit row               | per-user visibility and status, idempotency on re-run                                                                  | per-user retry — the retry unit silently becomes the whole run, and re-running re-executes the search and may duplicate cases |

**Recommendation: (a), the work queue item.** Three reasons.

First, it is the only option that preserves **per-user retry**. Under (b), recovering one failed user
means re-running the entire loop — re-executing the search, re-resolving targets, and re-creating cases
for users who already succeeded unless idempotency is airtight. That trades a working guarantee for a
harder problem.

Second, it delivers the **load smoothing** stated as an objective in the design note. Smoothing comes
from a queue drained by a worker pool, not from a loop iterating in a coroutine. Under (b), a 400-item
fan-out is 400 case creations inside one run; under (a) it is 400 queue rows drained at a controlled
rate.

Third, it **bounds the loop's own runtime**. The loop becomes targeting-and-enqueueing — short, bounded,
cheap to retry as a whole — while delivery stays in the machinery built for it.

**What (a) costs, stated plainly:**

- `createCase` becomes asynchronous: it returns an enqueued reference, not a case id. V0's launcher
  already returns immediately and runs the case in the background, so this is a smaller shift than it
  sounds — but the loop's outcome census must be reworded from *launched* to *queued*, and the
  step-level transcript will show enqueueing rather than creation.
- The **idempotency key must be redefined.** Today it is effectively (run, user). With discovered
  targets, a re-run may surface a *different* target set, so the key likely needs to be (run, entity, user) to stay
  meaningful.
- `Run` / `UserRun` stop being scheduler-specific concepts and become the general execution ledger —
  including for interactively triggered loops. **That is a naming and ownership question to settle
  deliberately**, not to discover through usage.
- The end-user authorization gate must fire at the right moment. If `createCase` enqueues, the gate must
  be checked **at enqueue time and re-checked at delivery time** — a grant can be revoked between the
  two, and a queue row that outlives a revocation must not deliver.

**If (b) is chosen instead**, then per-user retry must be explicitly declared a non-goal and the retry
unit documented as the whole run — so the loss is a decision on the record rather than a surprise during
the first incident.

## Positive consequences

- **Five of the seven V0 debts are resolved.** D1 (vendor coupling) disappears with the shared result
  schema; D4 (no run persistence) is answered by the scheduler's `Run` plus the case transcript; D5 (pagination) becomes
  safe and is in scope; D6 (duplication) disappears because there is one execution
  path; D7 (single trigger surface) is answered by initiation becoming a layer.
- **One execution path instead of two.** The symmetry invariant stops being something maintained by hand
  in two subsystems.
- **The loop inherits reliability it never had**: lease-based claim, retry, execution windows, orphan
  recovery — machinery that already exists and is already proven.
- **The scheduler gets simpler**, not more complex: it stops owning case creation, prompt delivery and
  user fan-out.
- **The scheduler gains targeting it never had**: it can now act on a computed population rather than a
  list known in advance.
- **A new business sequence becomes a configuration change**, not a code change in the runner — provided
  the required tools exist.
- **Run observability improves at both levels**: run status from the scheduler, step-level detail in the
  case transcript, with SSE streaming, existing UI rendering and the existing kill path.
- **Tool reuse is bidirectional**: `createCase`, `Targetter` and `UserGroupResolver` become available to
  ordinary LLM-backed agents too, not only to loops.
- **End users get an explanation.** A case arriving unannounced becomes a case with a visible origin and
  a named accountable human.

## Negative consequences

- **Gate 1 effectively disappears.** Running the loop as a namespace administrator means the search sees
  everything in the namespace. The first authorization gate stops constraining anything, and **`createCase`'s end-user
  check becomes the sole protection for end users.** That makes US 1
  security-critical, not merely a refactor: it must be mandatory, inside the tool, and covered by a test
  asserting refusal.
- **Borrowing a human administrator's identity has costs.** An audit trail will show a person as the
  actor for work they did not initiate; behaviour may vary depending on *which* administrator is picked;
  and the automation breaks or changes if that person is deactivated or loses the role. See the
  recommendation below.
- **Per-user execution granularity is at risk** — see the open point above. Resolving it wrongly trades a
  known reliability guarantee for an unknown one.
- **A data-model redesign with migration.** Existing `AgentConfig.loopConfig` payloads and existing
  scheduled prompts must be migrated; the redesign touches a persisted, user-visible model.
- **Larger blast radius for mistakes, by construction.** A configurable sequence with configurable
  fan-out can do more damage than a hardcoded pair, and more again once an administrator identity does
  the searching. Mitigated by the runtime invocation cap and the in-tool gate — but the risk is real.
- **A safety-critical invariant moves into a tool.** The end-user gate currently sits in
  `LoopWorkflowRunnerService.act`, where it is hard to bypass. Moving it into `createCase` is correct but
  makes that tool's contract load-bearing.
- **A breaking SDK change.** Differentiating the `Agent` contract affects a published type and requires a
  migration note for plugin authors.
- **Two subsystems are destabilised at once.** This is no longer a change to an experimental mode: it
  modifies the execution path of `ScheduledPrompt`, which is in production and outside the experimental
  carve-out that covers `ExecutionMode.LOOP`.
- **A standing risk of drifting into a workflow engine.** Every subsequent request — conditionals,
  branching, retries, sub-sequences — will be individually reasonable. The non-goals in
  `agentos-agent-loop-v1.md` must be enforced in review and revisited deliberately, not absorbed one
  feature at a time.

### Recommendation on the execution identity

Decision 11 settles *what kind* of identity runs the loop. One refinement is worth making explicit
before US 4: **which** administrator.

Selecting an arbitrary `isAdmin = true` user at run time makes execution non-deterministic (behaviour
varies with whoever is picked), couples the automation to an individual's employment, and attributes
machine actions to a named human in the audit trail.

Preferred refinement: a **stable designated principal per namespace** — a technical account holding
administrator rights, flagged as non-human. Same visibility, same decision, but deterministic, immune to
staff turnover and honest in the audit log. If that is too much for this sprint, the interim should at
least be a *recorded, stable* choice (persisted on the scheduler) rather than a lookup that may return a
different person on each run.

## Implementation

### Phase 1 — in parallel

| US       | Scope                                                                                                                                    | Owner            |
|----------|------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| **US 1** | BE — Tool to create a case between a user and an agent *(owns the end-user authorization gate; shape depends on the `UserRun` decision)* | Frédéric Delsert |
| **US 2** | BE — Tool to list all users of a user group                                                                                              | Thomas Martin    |
| **US 3** | FT — Copy JSON to clipboard from Workforce, restricted to Designers *(1 pt)*                                                             | Mathieu De Armey |

### Phase 2 — then

| US       | Scope                                                                                          | Owner            |
|----------|------------------------------------------------------------------------------------------------|------------------|
| **US 4** | Redesign agent config data model + replace `scheduler.run` by a case running an AgentLoop      |                  |
| **US 5** | Pagination                                                                                     |                  |
| **US 6** | IHM Studio *(5 pts)*                                                                           | Mathieu De Armey |
| **US 7** | Inform the end user that a case was initiated automatically, on behalf of the accountable user |                  |

#### US 7 — Automatic-origin disclosure on scheduled cases

**Need.** As an end user receiving a case I did not open, I need to see that it was started
automatically and on whose behalf, so I understand why it exists and whom to ask about it.

**Approach.**

- Resolve the accountable user from `Case.scheduledPromptId` → scheduled prompt → its creator. The link
  already exists (`wz-34012`); no new field on the case is required.
- Expose it as case metadata, not as a conversation message. **This is deliberate**: injecting a
  `MessageEvent` such as *"this case was created automatically by X"* would enter the agent's LLM
  context and invite the agent to respond to it. Origin is chrome, not conversation.
- Render it as a badge or banner in the case view, translated per the user's `preferredLanguage` (the
  mechanism already exists and is authoritative over provider values).

**Definition of done**

- A scheduled-origin case displays its automatic origin and the accountable user.
- An interactively created case displays nothing.
- The disclosure is not present in the agent's context and cannot be addressed by the agent.
- Graceful fallback when the scheduler was deleted or the accountable user deactivated — the case still
  states it was automatic, without a dangling name.
- Text is translated like other user-facing strings.

### Later sprint

| Scope                                                        | Prerequisite                                                                  |
|--------------------------------------------------------------|-------------------------------------------------------------------------------|
| Natural-language generation of loop configs                  | Write-time `loopConfig` validation; human confirmation before first execution |
| Expression language (JsonPath) for step-to-step data mapping | Declared mapping shape in the US 4 data model                                 |

### Open points before US 4

- **Fate of `UserRun`** — work queue item or ledger record. See the open point above; determines the
  retry unit and the shape of US 1.
- **Which administrator runs the loop** — arbitrary lookup vs. stable designated principal. See the
  recommendation above.
- **Fan-out expression** — explicit `forEach` recommended; confirm, or the first implementation decides
  it implicitly.
- **Write-time `loopConfig` validation** — not currently scheduled. Prerequisite for natural-language
  generation, and valuable on its own once the data model is a structured sequence. Candidate scope for
  US 6 (IHM Studio).
