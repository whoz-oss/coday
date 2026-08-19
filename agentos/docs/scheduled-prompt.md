# Scheduled Prompt

## Overview

A `ScheduledPrompt` fires a prompt against an agent on a recurring schedule, creating one
`ScheduledPromptRun` per slot and one `ScheduledPromptUserRun` per target user.

Execution is split into two phases handled by `SchedulerScanner` and `ScheduledPromptExecutor`:

- **Phase A — Claim** (`tickClaim`): discovers due prompts, inserts a Run, and materialises
  UserRuns via a single Cypher INSERT-SELECT.
- **Phase B — Consume** (`tickConsume`): claims PENDING UserRuns, creates a Case per user,
  injects the prompt, and monitors the launch.

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
| Between last `markTerminal()` and `checkCompletion()` | Run RUNNING, all UserRuns terminal | `recoverOrphanedRunningRuns` closes Run as DONE or FAILED on next tick | Next claim tick |

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

## `updateStatus` guard

`ScheduledPromptRunNodeNeo4jRepository.updateStatus` only writes when the Run is not already
in a terminal status (`DONE` or `FAILED`). This prevents `recoverOrphanedRunningRuns` from
overwriting `finishedAt` already set by `checkCompletion` when both race on the same Run.
