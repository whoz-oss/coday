# Scheduler → AgentLoop convergence: plan index and cross-part reconciliation

**Start here.**

| Document | Covers |
|---|---|
| [Decision record](./agentos-scheduler-agentloop-convergence.md) | **Authoritative.** What was decided and why. |
| [Part 1](./plan-part1-audience-and-translation.md) | Audience-tool widening; text-level translation; `scheduledPromptId` passthrough. |
| [Part 2](./plan-part2-loop-config-model.md) | Linear loop-config model; the fan-out tool; the runtime; write-time validation; the Agent contract. |
| [Part 3](./plan-part3-scheduler-convergence.md) | `ScheduledPrompt.loopConfig`; synthesized default; execution identity; `TARGETING`; Phase B collapse; item cap. |

Parts 1–3 were drafted independently. **This document records where they contradicted each
other and which reading wins.** Where a part disagrees with the resolution below, the
resolution wins; the part should be corrected as it is implemented.

The contradictions are concentrated in one place: the **seam between the audience step and the
fan-out step**. That is not an accident — it is the only place where three separately-drafted
concerns have to agree on a data shape. It is worth building first, end to end, for exactly
that reason.

---

## R1 — BLOCKER: the fan-out tool cannot consume the audience tool's output

Part 2 specifies `BatchCaseStarter`'s input as the **Whoz search-result shape**:

```json
"previousStepOutput": { "data": [ { "entityId": "...", "entityType": "...", "targets": [...] } ] }
```

Part 1 establishes that the audience tool returns a **flat array of users**:

```json
[ { "userId": "<uuid>", "externalId": "...", "role": "MEMBER" } ]
```

No `data` wrapper, no `entityId`, no `targets`. Wired as drafted, the scheduler's default config
fails at the first fan-out on every run.

The root cause is that part 2 carried the V0 shape forward — the very Whoz coupling (debt D1) that
ADR-64 decision 5 deletes. The decision record is explicit: *the contract between steps becomes
each tool's `outputSchema()` / `structuredOutput`.*

**Resolution.** `BatchCaseStarter` consumes a **list of user references**, matching
`ListAgentUsers.outputSchema()` — each element needs only `userId`. Entity-derived context is
*optional* per element, so a future search-driven loop can supply it without reshaping the tool.
The `activeContext` shaping stays in the tool (part 2 §2.3 is right about *where*, wrong about
*what*) and is emitted only when an element carries entity data.

---

## R2 — BLOCKER: the translation path has no end-to-end route

Part 1 states that `FreeCaseStarterForUserTool.Input` does **not** change and that
`translateFrom` / `scheduledPromptId` are **not exposed to LLMs** — correct, and well argued:
exposing them invites a model to guess a source language or inject an arbitrary scheduler id.
Part 1 therefore assumes the fan-out step *"will construct the `CaseStarterRequest` directly in
Kotlin"*.

Part 2 §3.3 does the opposite: `BatchCaseStarter` delegates through
`starterTool.executeWithJson(...)`. JSON can only carry fields that exist on `Input` — so
`translateFrom` and `scheduledPromptId` are silently dropped. Parity traps 4 and 5 both fail,
and both fail *quietly*: prompts arrive untranslated and `Case.scheduledPromptId` is null.

**Resolution.** `BatchCaseStarter` calls **`CaseStarterService` directly**, in Kotlin, building
`CaseStarterRequest` per element. Part 2 itself wavered here, noting that `ToolContext` does not
carry sibling tools and offering a construction-time workaround; the direct call removes the
problem rather than working around it. Authorization is unaffected — every gate lives inside
`CaseStarterService`, which is precisely why ADR-64 decision 10 put it there. What is lost is only
the agent *allowlist* enforced by `CaseStarter`, which is a guard against an LLM naming an
arbitrary agent; the scheduler's agent comes from `ScheduledPrompt.agentConfigId` and has already
been validated at claim time.

---

## R3 — MAJOR: three different spellings of a step

Part 2 defines `LoopStep(id, tool, input)` where `input` is `LoopStepInput(params, from)`.
Part 3's synthesized default writes `params` and `input` as **siblings** of `tool`. Part 2's own
example uses `params: { agentName: ... }` for the audience tool, while the real tool requires
`agentConfigId`. Part 3 prefixes tool names (`USER_GROUP__ListAgentUsers`); part 2 does not.

**Resolution.** Part 2's Kotlin model is canonical: `{ id, tool, input: { params, from } }`.
Part 3's JSON must be rewritten to nest `params` inside `input`. Tool names must be the names
actually resolved into the agent's tool set — which for config-backed integrations *are* prefixed,
so part 3 is right on prefixes and part 2's examples need correcting. The audience step's params
are `{ "agentConfigId": "<uuid>" }`.

This is exactly what write-time validation (part 2 §5.2) exists to catch, which is an argument for
implementing validation early rather than last.

---

## R4 — MAJOR: write-time tool validation checks the wrong map

Part 2 §5.2 validates `step.tool in entity.integrations`. The `integrations` map is keyed by
**integration name** (`CASE`, `USER_GROUP`) with tool names in the *values*. Every valid config
would be rejected.

**Resolution.** Validate against the flattened set of granted tool names, accounting for the
`CONFIG__tool` prefixing. If that cannot be resolved reliably at write time, **drop the tool-name
check** and keep only the structural checks — a wrong check is worse than no check. The special
case for `"BatchCaseStarter"` in part 2's draft is a symptom of the same confusion and disappears
once the tool is a normal grant of the `CASE` integration.

---

## R5 — MAJOR: `AgentLoopPayload` is deleted and still referenced

Part 2 §2.1 deletes `AgentLoopPayload.kt`; §5.3 then parses legacy configs via
`AgentLoopPayload.toLoopConfig()`.

**Resolution.** There is **nothing to migrate.** Verified against the repo: no filesystem YAML
agent uses `executionMode: LOOP`, and the only persisted `loopConfig` payloads belong to
experimental agents under the carve-out that explicitly places `ExecutionMode.LOOP` outside the
stability contract. Delete `AgentLoopPayload` outright, with no legacy parsing path. A malformed
or legacy config fails loudly at parse time with a clear message — appropriate for an experimental
mode, and far cheaper than a compatibility shim nobody needs.

---

## R6 — MINOR: the cap value disagrees

Part 2 §3.2 illustrates the cap as 50; part 3 §D raises it to 500. Part 3 wins — 50 is the current
value and is the parity trap. Part 2's example message must be regenerated from the injected
config rather than hardcoded.

---

## Build order

The reconciliations above push one item to the front.

1. **The seam.** Audience tool widening (part 1 §1) *and* `BatchCaseStarter` consuming its exact
   output (R1), with the parity test. Every contradiction above lives here; building it first
   turns a design disagreement into a passing test.
2. **Translation + `scheduledPromptId`** (part 1 §2) via the direct `CaseStarterService` call
   (R2). Independently mergeable and independently valuable.
3. **The linear model, runtime and write-time validation** (part 2, with R3/R4/R5 applied).
4. **The scheduler side** (part 3): `loopConfig`, synthesized default, identity at claim,
   `TARGETING`, Phase B collapse, cap.

Items 1–3 leave the scheduler untouched and are reversible. Item 4 is the production migration.
Keeping that boundary sharp is the main risk control available on this sprint.

---

## R7 — Cardinality is settled: one case per entity

The fan-out tool is a **pure map** over its input list — one case per element, **no
deduplication by user**. A manager reached by three entities receives three cases.

This removes the idempotency question from the model rather than answering it: under decision 1
the converged path writes no `UserRun` rows, so the `(runId, userId)` UNIQUE constraint — which
would have silently collapsed those three into one — never applies. No new key is needed.

For the synthesized default the audience step returns users, so one case per element *is* one per
user. The two readings coincide and the tool needs no mode flag.

## Still unanswered

Carried from the decision record; none blocks item 1.

- **What bounds a loop run?** The `TARGETING` orphan threshold is the real answer and needs a
  deliberate value, not the inherited 5-minute CLAIMED threshold.
- **Is `createdBy` actually populated** on production `ScheduledPrompt` rows? The identity guard
  disables any prompt lacking it — spot-check before shipping part 3.
- **Do platform-scope agents surface in namespace-scoped agent lists?** Determines whether the
  seeded loop agent needs hiding.
