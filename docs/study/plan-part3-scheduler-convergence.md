# Part 3 — Scheduler convergence: `loopConfig`, execution identity, `TARGETING`, Phase B collapse, item cap

> Part of the implementation plan for
> [`agentos-scheduler-agentloop-convergence.md`](./agentos-scheduler-agentloop-convergence.md),
> which is the authoritative decision record. Do not re-litigate its decisions here.
>
> Companion parts: [part 1 — audience + translation](./plan-part1-audience-and-translation.md),
> [part 2 — loop config model](./plan-part2-loop-config-model.md).
>
> **This document reconciles three drafts** that disagreed on six points. Each reconciliation is
> recorded in [§6](#6-reconciled-conflicts) with its reasoning, so the rejected option is not
> silently re-proposed later.

---

## A — `ScheduledPrompt.loopConfig` + synthesized default

### A.1 Domain and persistence

Follow the `AgentConfigNode` precedent exactly: `JsonNode` in the domain, serialized `String` in
Neo4j.

```kotlin
// ScheduledPrompt.kt — add after externalMetadata
val loopConfig: JsonNode? = null,

// ScheduledPromptNode.kt — add alongside externalMetadataJson
val loopConfigJson: String? = null,
// toDomain():   loopConfig    = loopConfigJson?.let { MAPPER.readTree(it) }
// fromDomain(): loopConfigJson = scheduledPrompt.loopConfig?.let { MAPPER.writeValueAsString(it) }
```

Reuse the existing `MAPPER` in `ScheduledPromptNode.Companion`.

**No backfill.** Neo4j returns absent properties as `null`; SDN maps that to `null`; `null` triggers
synthesis. No constraint or index on the field, so no schema-initializer change.

### A.2 Not exposed in the public API this sprint

Keep `loopConfig` out of `ScheduledPromptDto` and out of the OpenAPI schema. The authoring surface
(Studio) is an explicit non-goal, and exposing a writable field with no UI and no write-time
validation of *this* entity invites malformed configs we would then have to support. The field is
reachable internally; it becomes public when Studio needs it.

### A.3 Synthesis — a default value, never a branch

Synthesis lives in its own `@Service`, `ScheduledPromptLoopConfigSynthesizer`, not inline in the
executor. One method, total:

```kotlin
fun synthesize(scheduledPrompt: ScheduledPrompt, promptContent: String, title: String): JsonNode
```

The call site has **no conditional**:

```kotlin
val effectiveLoopConfig = scheduledPrompt.loopConfig ?: synthesizer.synthesize(sp, content, title)
```

That `?:` is the entire branch, and it produces a value rather than selecting a code path — which is
the point of decision 3. Everything downstream sees one shape.

The synthesized default is the two-step linear config defined in part 2:

```json
{
  "version": 1,
  "steps": [
    { "id": "list-audience",
      "tool": "USER_GROUP__ListAgentUsers",
      "params": { "agentConfigId": "<sp.agentConfigId>" } },
    { "id": "fan-out",
      "tool": "CASE__BatchCaseStarter",
      "input": { "from": "list-audience" },
      "params": { "agentName": "<name of sp.agentConfigId>",
                  "prompt": "<promptContent>",
                  "title": "<title>",
                  "translateFrom": "<prompt.sourceLanguage>" } }
  ]
}
```

`translateFrom` is what carries parity trap 4 — see part 1 §2.

> **Trap.** `sp.agentConfigId` is the **target** agent each end user talks to. Never read
> `AgentConfig.loopConfig` from it: that field, if set, is the target agent's own loop config and has
> nothing to do with the scheduler's fan-out.

### A.4 The seeded loop agent

A platform-scoped `AgentConfig` (`namespaceId = null`, `executionMode = LOOP`) carries the tool
grants. Seeded by a new `SchedulerLoopAgentSeeder` following `CaseIntegrationConfigSeeder`:
existence-not-content, create-only, tolerant of the unique-constraint conflict under concurrency,
ordered after the integration-config seeders it depends on.

```kotlin
integrations = mapOf(
    "USER_GROUP" to listOf("ListAgentUsers"),
    "CASE" to listOf("BatchCaseStarter"),
)
```

The loop agent is the **executor**, not a target: its case is created directly by the scheduler, so
the deployment graph is irrelevant for it. The deployment graph is checked per end user, inside
`CaseStarterService`, during fan-out — which is exactly where decision 10 puts it.

Verify before naming it that platform-scope agents do not surface in namespace-scoped agent lists.
If they do, that is a separate (and more general) problem than this sprint.

---

## B — Execution identity, resolved and validated in `claim()`

### B.1 Placement

Immediately after the existing agent-config guard in `SchedulerScanner.claim()`, **before the Run is
inserted**. The existing guard is the template:

```kotlin
val agentConfig = agentConfigService.findById(scheduledPrompt.agentConfigId)
if (agentConfig == null || !agentConfig.enabled) {
    logger.warn { "[SchedulerScanner] AgentConfig ... — disabling sp=${scheduledPrompt.id} ..." }
    scheduledPromptRepository.updateEnabled(scheduledPrompt.id, false)
    return
}
```

Identity resolution returns the `User`, which is then passed into `materialize()` so it is read once
per run rather than twice.

### B.2 The three failure cases — and why two disable but one does not

| # | Condition | Action | Why |
|---|-----------|--------|-----|
| 1 | `createdBy` is `null` | disable the prompt | `AgentOsAuditorAware` returns `Optional.empty()` outside an HTTP request context, so prompts seeded by migration or bootstrap have no creator. This will never become non-null on its own; retrying forever is noise. |
| 2 | creator removed | disable the prompt | There is no `disabled`/`active` flag on `User`, only `metadata.removed`. A removed user is not reinstated automatically. |
| 3 | creator lacks namespace WRITE | **fail the run only** | A permission can be granted back. Disabling would require a human to notice and re-enable, turning a recoverable condition into a permanent one. |

Case 3 is the asymmetry the decision record predicted:
`OverlayScopeAuthorizer.authorizeCreateOrThrow` requires only `Action.READ` to create a *user-scoped*
prompt, while `CaseStarterService` requires `Action.WRITE` to start a case for anyone else. So a
user-scoped prompt's creator can create it but can only fan out to themselves. The authorization is
correct; let it refuse. What matters is that the refusal is **legible** — one run-level error naming
WRITE explicitly, not N anonymous per-user failures.

**Never fall back** to a super-admin or to "any namespace admin". A fallback silently escalates
privilege at precisely the moment something is already wrong.

### B.3 Mechanics

`SchedulerScanner` gains `UserService` and `PermissionService` constructor parameters.
`UserService` has no `findById(UUID)` on the interface today (only the repository) — add it; it is
additive and breaks nothing.

---

## C — `TARGETING` status and the Phase B collapse

### C.1 The trap

`recoverOrphanedRunningRuns` closes any `RUNNING` run with no `PENDING`/`RUNNING` UserRun:

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

The converged path creates **no UserRun rows at all**, so the `NOT EXISTS` is always true and every
run is closed `DONE` within ~30s of starting — reported successful having delivered nothing.

### C.2 Lifecycle: `CLAIMED → TARGETING → DONE | FAILED`

The converged path **never enters `RUNNING`**. Going `CLAIMED → RUNNING → TARGETING` would leave a
window in which the run is `RUNNING` with no UserRuns and the sweeper can see it. Skipping `RUNNING`
closes that window by construction rather than by timing.

```kotlin
enum class RunStatus {
    CLAIMED,
    /** Converged path: the loop case is running. No UserRun rows exist. */
    TARGETING,
    /** Legacy path only. Never written by converged code; retained so old nodes deserialize. */
    RUNNING,
    SKIPPED,
    DONE,
    FAILED,
}
```

`findSettledRunning` is therefore **unchanged** — and its KDoc must say why, explicitly: *TARGETING
runs have no UserRuns by design and must never be added to this query; they are swept by
`recoverOrphanedTargetingRuns`.* Without that comment the bug is one plausible edit away.

### C.3 Cypher changes

**Overlap guard** — must treat `TARGETING` as active, or a second tick claims a new run while the
loop case is still executing:

```cypher
-- was:  AND r.status IN ['CLAIMED', 'RUNNING']
AND r.status IN ['CLAIMED', 'TARGETING', 'RUNNING']
```

**`updateStatus` terminal guard** — `TARGETING` is non-terminal, so no change is required. Note in
passing that `SKIPPED` is absent from the guard and so can be overwritten; that is a pre-existing
defect, out of scope here, worth its own issue.

**`findByStatusAndCreatedBefore`** — unchanged; it takes a status string, so it serves the new sweep
directly.

**No backfill.** `status` is a String property and `TARGETING` is only ever written by new code.

### C.4 Orphan sweep for `TARGETING`

A crash after the loop case starts leaves the run `TARGETING` forever. Add a sweep alongside the
existing two in `processClaim()`, wrapped in `runCatching` like its siblings.

**It must not reuse the 5-minute `ORPHAN_THRESHOLD`.** That threshold is calibrated for a gap of
seconds between the Run insert and `materialize()`. A loop case legitimately runs for minutes. A
separate, longer, configurable `targetingOrphanThresholdMinutes` is required — and it is the real
upper bound on a loop run, so it should be chosen deliberately rather than inherited.

### C.5 The collapse

`materialize()` stops fanning out and instead, in one `@Transactional` block: resolves the effective
loop config, creates **one** case with `scheduledPromptId` set (parity trap 5), grants the creator
ADMIN on it, injects `@<loop-agent> <config JSON>` as the first message, and transitions the run
`CLAIMED → TARGETING`.

Monitoring happens **after** the transaction commits. `@Transactional` does not span coroutine
suspension points, so the transactional setup must be a separate, synchronously-called function —
not a `suspend` function annotated `@Transactional`.

Monitoring reuses `monitorLaunch` verbatim in mechanism, rewired from UserRun to Run:

```kotlin
val observedStatus = withTimeoutOrNull(timeoutMs) {
    runtime.statusFlow.first { it == CaseStatus.IDLE || it.isTerminal() }
}
```

Outcome mapping — note this is **not** the old UserRun mapping:

| Observed | Run status | Why |
|----------|-----------|-----|
| `IDLE` | `DONE` | the loop finished its turn |
| `ERROR` / `KILLED` | `FAILED` | with the status in the error field |
| timeout | **stays `TARGETING`** | monitoring released; the orphan sweep decides later |

Mapping a timeout to `DONE` would be a false success — exactly the failure mode `TARGETING` exists to
prevent. And the timeout itself must **not** be `launchTimeoutSeconds` (default 30s): that value is
calibrated as a health check on a single case launch, not as a bound on a fan-out. Add a separate
`loopCaseTimeoutSeconds`.

### C.6 Dead vs retained in `ScheduledPromptExecutor`

**Deleted:** `startProcessingLoop`, `startUserRunPoller`, `startUserRunWorkers`, `processUserRun`,
`resolveRunContext`, `createAndInjectCase`, `translatePromptForUser`, `hasAgentAccess`,
`UserRunContext`, the `consumePaused` flag with `pauseConsume`/`resumeConsume`/`isConsumePaused`, the
`@PostConstruct start()` / `@PreDestroy stop()` / `restart()` / `isRunning()` lifecycle, and
`SchedulerScanner.tickWatchdog`.

**Retained:** `materialize` (reshaped), `awaitLaunch` and `monitorLaunch` (rewired to Run),
`closeUserRun` → `closeRun`, `markFailed` → `markRunFailed`.

The executor still needs a `CoroutineScope` to launch monitoring, but a plain
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` created at construction — no two-phase lifecycle,
because there is no persistent loop to keep alive or restart.

**Delete `tickWatchdog` and `isRunning`/`restart` in the same commit.** They are mutually dependent,
and `restart()` cancels the scope — which on the converged path would cancel every in-flight
monitoring coroutine.

### C.7 `ScheduledPromptUserRun` survives untouched

No rows are written on the converged path, but the domain class, node, repositories and
schema-initializer all stay. Deleting a persisted Neo4j label during a behavioural migration carries
the same risk the decision record's non-goal identifies for renaming one, and existing rows are
historical records. Mark the write methods `@Deprecated` with a pointer to this document. Removal is
a later chore, once convergence is stable and legacy rows are archived.

### C.8 `SchedulerEndpoint`

The `consume` phase pauses a loop that no longer exists. The endpoint is JMX-exposed, so external
automation may be scripted against it. Accept `consume` for one release as a logged no-op, drop
`consumePaused` from `status()`, then remove. `claim` is unchanged and remains the operator lever
that matters.

---

## D — The item cap

`agentos.limits.agent-loop-max-items` is explicitly `50` in `application.yml` while `materialize()`
has no cap at all — so convergence would silently shrink every audience above 50.

Raise it to **500**, enforced **inside the fan-out tool** and nowhere else. Do not add an audience
pre-count to `materialize()`: that would reintroduce scheduler-side audience computation, which is
the precise thing convergence removes, and it would need its own parity test against the tool.

A breach **fails the run loudly** — the tool throws before starting any case, the loop case goes
`ERROR`, and the run goes `FAILED` with the count and the limit in the message. No partial delivery.
Update the `LimitsConfigProperties` KDoc, which currently promises the opposite ("entities beyond the
limit are ignored and reported in the loop summary").

---

## 5. Tests

`SchedulerScannerUnitSpec` — null `createdBy` disables and inserts no run; removed creator disables;
creator without WRITE fails the run **without** disabling; valid creator reaches `TARGETING`; the
overlap guard fires on a `TARGETING` run; the `TARGETING` orphan sweep respects its own threshold.
The `scanner(...)` factory helpers need the new dependencies.

`ScheduledPromptExecutorUnitSpec` — `materialize` creates exactly one case carrying
`scheduledPromptId` and reaches `TARGETING`; an explicit `loopConfig` is used verbatim; a null one is
synthesized; `IDLE` → `DONE`; `ERROR` → `FAILED`; timeout leaves `TARGETING`. Phase B tests are
deleted with the methods they cover.

`ScheduledPromptBatchScenarioSpec` — **rewritten, not deleted.** It is the only end-to-end scheduler
test; deleting it would leave the converged path with no integration coverage. Scope the rewrite
explicitly.

---

## 6. Reconciled conflicts

Three drafts disagreed. Recorded so the rejected option is not re-proposed.

1. **Lifecycle.** `CLAIMED → RUNNING → TARGETING` vs `CLAIMED → TARGETING`. **Chose the latter** —
   the intermediate `RUNNING` leaves a window where the sweeper can close a live run. One draft
   proposed the window and then flagged it as its own top risk.
2. **Timeout mapping.** `DONE` vs stays `TARGETING`. **Chose `TARGETING`** — mapping a timeout to
   `DONE` is a false success, the exact failure mode this work exists to prevent.
3. **Timeout value.** Reuse `launchTimeoutSeconds` (30s) vs a new property. **Chose a new
   property** — 30s is a health check on one case launch, not a bound on a fan-out.
4. **`TARGETING` orphan threshold.** Reuse `ORPHAN_THRESHOLD` (5 min) vs its own. **Chose its own** —
   5 min is calibrated for a seconds-long gap and would fail healthy long runs.
5. **Cap enforcement.** Pre-count in `materialize()` vs tool-only. **Chose tool-only** — a pre-count
   reintroduces scheduler-side audience computation and needs its own parity test.
6. **Identity failure handling.** Disable on all three vs fail-only on the permission case. **Chose
   fail-only for permission** — it is recoverable, and disabling turns a transient condition into one
   needing human intervention.

## 7. Open technical questions

- **What bounds a loop run?** `targetingOrphanThresholdMinutes` is the real answer and needs a
  deliberate value, not an inherited one.
- **Is `@CreatedBy` actually populated on `ScheduledPromptNode` in production data?** The mapping is
  correct in code; existing rows should be spot-checked before shipping, since the identity guard
  would disable any prompt lacking it.
- **Do platform-scope agents surface in namespace-scoped agent lists?** Determines whether the seeded
  loop agent needs hiding.
