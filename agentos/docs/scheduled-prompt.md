# Scheduled Prompt

## Overview

A `ScheduledPrompt` fires a prompt against an agent on a recurring schedule, creating one
`ScheduledPromptRun` per slot and one `ScheduledPromptUserRun` per target user.

Execution is split into two phases handled by `SchedulerScanner` and `ScheduledPromptExecutor`:

- **Phase A — Claim** (`tickClaim`): discovers due prompts, inserts a Run, and materialises
  UserRuns via a single Cypher INSERT-SELECT.
- **Phase B — Consume** (continuous loop in `ScheduledPromptExecutor`): a producer +
  channel + worker-pool loop started by `@PostConstruct` claims PENDING UserRuns, creates
  a Case per user, injects the prompt, and monitors the launch. This loop runs
  independently of the claim tick and is kept alive by a watchdog (see below).

## UserRun lifecycle

```
PENDING → RUNNING → DONE
                  → TIMEOUT   (Case still running after launchTimeoutSeconds — monitoring released)
                  → FAILED    (Case ERROR/KILLED, or exception during Case creation)
```

TIMEOUT is terminal. A Run whose UserRuns are all DONE/TIMEOUT closes as DONE.
A Run with at least one FAILED UserRun closes as FAILED.

## Occurrence quota

`maxOccurrenceCount` counts **slots**, not executions. SKIPPED runs (overlap with an active
run) consume an occurrence — without this, overlapping slots would allow the prompt to
execute beyond its intended temporal window.

The count is scoped to `scheduledFor >= planning.startDate` so that changing the start date
resets the quota for the new planning window.

## Crash recovery

All recovery is passive — no dedicated recovery process, no manual intervention.
Each mechanism fires automatically on the next tick.

| Crash window | Stuck state | Recovery mechanism | Delay |
|---|---|---|---|
| Between `runRepository.insert()` and `runCatching { executor.materialize() }` | Run CLAIMED, no UserRuns created | `recoverOrphanedClaimedRuns` marks FAILED after `ORPHAN_THRESHOLD` (5 min) | ~5 min |
| Inside `materialize()` (transactional) | Run CLAIMED (transaction rolled back), no UserRuns | Inline `runCatching` marks FAILED immediately if instance alive; otherwise same as above | Immediate or ~5 min |
| Between `materialize()` and `markTerminal()` | UserRun RUNNING with expired lease | `claimBatch` reclaims on lease expiry — new Case created (at-least-once) | `leaseMinutes` |
| Between last `markTerminal()` and Run closure | Run RUNNING, all UserRuns terminal | `recoverOrphanedRunningRuns` closes Run as DONE or FAILED on next tick | Next claim tick |

### Delivery guarantee

UserRun execution is **at-least-once**: if an instance crashes after creating a Case but
before `markTerminal`, the lease expires and a subsequent `claimBatch` creates a second
Case for the same user. This is acceptable for the scheduled-prompt use case (duplicate
conversation, no data corruption). Exactly-once would require a UNIQUE constraint on Case
keyed by `(runId, userId)`.

### Invariant: `leaseMinutes * 60 > launchTimeoutSeconds`

The lease must outlive the launch timeout. A shorter lease allows UserRuns to be reclaimed
before `monitorLaunch` completes, causing double execution within the same slot.
Enforced at startup via `require()` in `SchedulerScanner.logStartup()`.

## Consumer-loop watchdog

`SchedulerScanner.tickWatchdog` fires on the same interval as `tickClaim`. It calls
`ScheduledPromptExecutor.isRunning()` and, if the consumer loop has died unexpectedly
(e.g. due to an uncaught exception escaping the scope), calls `ScheduledPromptExecutor.restart()`
to recreate the `CoroutineScope` and relaunch the producer + worker pool. This ensures
Phase B resumes automatically without operator intervention.

## `updateStatus` guard

`ScheduledPromptRunNodeNeo4jRepository.updateStatus` only writes when the Run is not already
in a terminal status (`DONE` or `FAILED`). This prevents concurrent calls to
`recoverOrphanedRunningRuns` across instances from overwriting a `finishedAt` already set
by a previous invocation.

## Loop mode

By default a scheduled prompt opens one case per user of the audience of its agent. In loop mode it opens a
**single case**, driven by a LOOP agent, which searches for entities and starts one case per hit for the
users concerned (an `AgentLoop` run, see `AgentLoop` and `LoopWorkflowRunnerService`).

### Scheduler fields

| Field | Meaning |
|---|---|
| `agentLoopName` | Name of the LOOP agent that drives the run, resolved in the namespace then at platform level. Setting it switches the prompt to loop mode. |
| `loopConfiguration.searchToolName` | Exact name of the search tool as granted to the loop agent (integration prefix included, e.g. `WHOZ__SearchTalents`). |
| `loopConfiguration.searchToolArgs` | JSON object passed as the input of the search tool (none = `{}`). |
| `agentConfigId` + prompt template | The agent started for each search hit, and the prompt it receives. They are *not* what the scheduler runs itself. |

`agentLoopName` and `loopConfiguration` are set together (or neither).

### What a run does

- **Phase A** materialises the audience of the *loop agent*, not of `agentConfigId`: the loop agent is deployed to
  a user group holding one technical user, hence one UserRun and one loop case. The agent is not checked for
  its number of users. An unknown agent, or one that is not a LOOP agent, fails the Run with that reason.
- **Phase B** creates the case for that technical user (title = the scheduled prompt's name, linked to it, ADMIN
  granted, monitored as usual) and sends the message `@<loop agent> Run scheduled prompt '<name>'`. The access
  check applies to the loop agent. The prompt is for the search targets, not for the technical user: it is
  neither translated nor enriched with a user context, and the user context provider is not called. The targets
  get theirs when the loop starts their cases.

### loopData and the loop agent's loopConfig

The loop agent's `loopConfig` describes the *process*, the message carries the *data*. The scheduler writes the
following `loopData` in the session context of the message (constants in `LoopDataKeys`):

| `loopData` key | Value |
|---|---|
| `searchToolName` | `loopConfiguration.searchToolName` |
| `searchToolArgs` | `loopConfiguration.searchToolArgs`, or `{}` |
| `agentName` | name of the agent of `agentConfigId` |
| `prompt` | content of the prompt template, as is |
| `scheduledPromptId` | id of the scheduled prompt |

The `loopConfig` of the loop agent (`AgentConfig.loopConfig`) reads it through `${loopData.key}` placeholders:

```json
{
  "steps": [
    { "id": "search", "type": "SEARCH", "tool": "${loopData.searchToolName}", "args": "${loopData.searchToolArgs}" },
    { "id": "start-cases", "type": "START_CASES", "tool": "FreeCaseStarterForUser",
      "args": { "agentName": "${loopData.agentName}", "prompt": "${loopData.prompt}" },
      "scheduledPromptId": "${loopData.scheduledPromptId}" }
  ]
}
```

Placeholder rules (`LoopPlaceholders`):

- `${loopData}` or `${loopData.k1.k2...}`, keys made of letters, digits, `_` and `-`; no index, default value,
  operator or escaping. Resolved in `tool`, `scheduledPromptId` and every string value of `args`, at any depth;
  never in keys, `id` or `type`.
- A string that is exactly one placeholder takes the value as it is, with its type (object, array, number,
  boolean, null, string). Inside a longer text only scalars can be interpolated.
- A missing key, a non-scalar interpolated in a text and a malformed `${` are errors. What is inserted is never
  scanned again.

Step rules (`LoopStepsTranslator`), checked when the run starts: exactly a `SEARCH` then a `START_CASES` step
with distinct ids; the `START_CASES` tool is a `FreeCaseStarterForUser` tool (bare or `CONFIG__`-prefixed);
its `args` hold `agentName` and `prompt` (and optionally `title`), nothing else (`userId` and `sessionContext`
are set by the loop for each hit); `scheduledPromptId` resolves to a UUID. Any violation is reported as a
warning in the loop case and nothing runs.

A `loopConfig` without a `steps` field keeps the legacy format (`search` / `act`), independent of any
`loopData`; it is what a run launched from the UI uses.

### Prerequisites

- An agent in `LOOP` mode, named as `agentLoopName`, enabled, with the `loopConfig` above.
- Its integrations grant the search tool and `CASE: [FreeCaseStarterForUser]`.
- It is deployed to a user group with **one technical user**, who is an ADMIN of the namespace (needed to
  start cases for other users).
- The agent of `agentConfigId` is deployed to the users the search returns; those who are not are counted as
  refused in the summary of the loop case.

### Cases started by the loop and their link

Each case the loop starts is linked to the scheduled prompt (`Case.scheduledPromptId`) right after it is
created, with a targeted write-once update (`CaseService.assignScheduledPrompt`). A link that fails does not
fail the start: it is counted in the summary ("Started but not linked to the scheduled prompt"). A known race
remains: `CaseServiceImpl` saves the whole case on each status change, from a copy read just before, so a save
started before the link and finished after it can write the link away. The window is a few milliseconds and the
loop cannot see it; the real fix is a targeted status update.

### Limits

- At most `agentos.limits.agent-loop-max-items` (50) hits are processed, from the **first page** of results
  only; the summary says when more exist.
- Delivery is at-least-once, like any UserRun: a crash after the loop case is created can run the loop again
  and duplicate the fan-out.
- The UserRun only monitors the *launch*: a loop longer than `launchTimeoutSeconds` ends as `TIMEOUT` and the
  Run as `DONE`; a loop that aborts ends its case `IDLE`, so its UserRun is `DONE`. The reasons are in the loop
  case, not in the Run.
- Nothing validates the `loopConfig` or the `loopData` when a scheduled prompt is saved: errors show up when
  the loop runs (validation at save time is for a next change).
