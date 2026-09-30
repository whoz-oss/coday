# Durable agent attempt model (factory-service, Lot C durable-execution)

Session `c5f49c91` — base `4cad013`. 12 files, +1809/-0, all additive.

## What changed and why

The factory-service now models a durable agent execution attempt end to end: a
`DurableAgentAttempt` aggregate that records a bridge-dispatched execution, an atomic
claim that lets exactly one worker own an attempt at a time, and a lease-fenced
finalization that rejects any worker whose lease token diverged, expired, or was
preempted. Everything is keyed on the identifiers supplied by the bridge
(`attemptId`, `caseId`, `capabilityToken`, `ownerToken`/`leaseToken`,
`turnCorrelation`, `commandId`, `namespaceId`, `workflowId`, `stepId`,
`attemptNumber`, `agentName`, `failureCode`, `lastObservedEventId`) — the model adds
nothing of its own on top of them.

This is a **new sibling aggregate**, not a change to the existing result-path types.
`AgentStepAttemptNode` and the whole `AgentStepResult*` stack keep their
`running`/`completed` lifecycle and their tests untouched; the durable-execution
lifecycle lives next to them under the same `agentattempt` package. `SessionRunService`
was left alone — the service KDoc states the SSE/execution adapter (built in parallel)
is the intended caller. Nothing under `agentos/` or the adapter package was touched.

Three guarantees carry the lot:

1. **Atomic claim.** `claim` serialises competing claimants on a process-local
   `ReentrantLock` held around a `REQUIRES_NEW` transaction (the exact scaffolding of
   `Neo4jLeaseRepository`), then applies an atomic Cypher compare-and-set. Exactly one
   concurrent claimant wins; the losers get `AttemptClaimConflictException`
   (`ATTEMPT_CLAIM_CONFLICT`, HTTP 409). A repeated claim by the *same* owner is
   idempotent. A non-terminal attempt whose `leaseExpiresAt` has passed can be
   re-claimed by a new owner — the claim rotates `ownerToken`, which is what fences the
   previous worker out of finalization.
2. **Fencing at finalization.** `finalize` (and `transition`) run owner-guarded CAS
   statements (`WHERE a.ownerToken = $ownerToken AND NOT a.status IN [<terminals>]`).
   When the CAS matches nothing, the node is read back to disambiguate: divergent
   `ownerToken` → `AttemptLeaseFencingException` (`ATTEMPT_LEASE_FENCED`, 409); same
   terminal target already reached by the same owner → the existing record, returned as
   an idempotent replay; anything else → `InvalidAttemptTransitionException`.
3. **State machine.** `pending → claiming → starting → running → waiting_human →
   {succeeded | failed | indeterminate | interrupted}`. The encoded invariant:
   `SUCCEEDED` is reachable **only** from `RUNNING` and `WAITING_HUMAN`, so a timeout,
   an incomplete result, or an unknown outcome can never finalize as a success — those
   paths land on `INDETERMINATE` (or `FAILED`/`INTERRUPTED`). The four terminal states
   have no outgoing transitions. `finalize` requires a terminal target; `transition`
   rejects one.

Idempotence is by `attemptId`: the Neo4j node id is the composite key
`(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId)`, and
`register` is a `MERGE ... ON CREATE SET`, so a re-submission resolves to the same node
and returns the pre-existing record without clobbering a live state.

## Files

Domain (`factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/`):

- `AgentAttemptStatus.kt` — the enum + `ALLOWED_TRANSITIONS` map, `terminal`,
  `isSuccess`, `canTransitionTo`, `fromDbValue`; mirrors the shape of `WorkUnitState`.
- `DurableAgentAttempt.kt` — the domain record: bridge identity fields, lifecycle
  fields (`status`, `failureCode`, `resultEvidenceId`, `lastObservedEventId`,
  `revision`), timestamps, and `leaseExpiresAt`. KDoc documents the
  `ownerToken`/`leaseToken` alias and notes the bounded-dedup-set extension point for
  `lastObservedEventId` (the scalar high-water mark is the stored form).
- `DurableAgentAttemptExceptions.kt` — `DurableAgentAttemptErrorCodes` plus four
  exceptions extending the existing `AgentAttemptException`:
  `AttemptClaimConflictException` (409), `AttemptLeaseFencingException` (409),
  `InvalidAttemptTransitionException` (409), `AttemptNotFoundException` (404). Tests
  assert on `errorCode`, never on messages.

Persistence (`.../agentattempt/persistence/`):

- `DurableAgentAttemptNode.kt` — `@Node("DurableAgentAttempt")` SDN projection,
  `compositeId(...)` (pipe-separated), `toDomain()` / `fromDomain(scope, attempt)`.
- `DurableAgentAttemptRepository.kt` — the port: `register`, `find`, `claim`,
  `transition`, `finalize`, all scoped by `TenantScope`.
- `SpringDataNeo4jDurableAgentAttemptRepository.kt` — the Cypher: `MERGE` register,
  claim CAS (pending, same-owner re-claim, or expired-lease preemption),
  owner-guarded `transition`, owner-guarded `finalize`. Every mutation returns
  `count(a)` so the caller can tell a matched CAS (1) from a fenced/conflicted one (0).
- `Neo4jDurableAgentAttemptRepository.kt` — the `@Repository` implementation: claim
  lock + `REQUIRES_NEW` template, in-domain state-machine validation before the CAS,
  and the read-back disambiguation (`disambiguateFailedCas`) that decides between
  fencing, idempotent replay, and invalid transition.

Service (`.../agentattempt/service/`):

- `DurableAgentAttemptService.kt` — transactional façade (`register`, `claim` with
  optional `leaseTtlMs`, `transition`, `finalize`, `find`). Deliberately **not** wired
  into `SessionRunService`; the KDoc names the parallel-built SSE/execution adapter as
  the intended caller.

Tests (`factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/`):

- `domain/AgentAttemptStateMachineTest.kt` — pure unit test of the transition map:
  every allowed transition accepted, `SUCCEEDED` reachable only from
  `RUNNING`/`WAITING_HUMAN`, terminal states closed, `isSuccess`/`terminal` flags,
  `dbValue` round-trip.
- `DurableAgentAttemptClaimConcurrencyTest.kt` — real-concurrency integration test
  (`Neo4jDomainIntegrationTest`): 8 threads gated on a latch claim one attempt with
  distinct `ownerToken`s; exactly one wins, seven get `ATTEMPT_CLAIM_CONFLICT`; plus a
  same-owner re-claim idempotence case.
- `DurableAgentAttemptFencingTest.kt` — integration tests: divergent-token finalize is
  fenced and leaves the node untouched; an expired-lease preemption rotates the owner
  and fences the old worker; correct-owner finalize to `SUCCEEDED` persists
  `completedAt`/`resultEvidenceId`/`lastObservedEventId` and replays idempotently;
  `pending → succeeded` and `starting → succeeded` are rejected with
  `ATTEMPT_INVALID_TRANSITION`, and the timeout path finalizes as `INDETERMINATE`;
  re-registering the same `attemptId` leaves exactly one node.

Spec:

- `specs/c5f49c91_durable-agent-attempt-model.md` — the plan this implementation
  followed (frozen decisions, reference patterns copied from the lease and result
  repositories, acceptance-criteria-to-test mapping).

## How to use / verify

The entry point is `DurableAgentAttemptService`. Typical flow:

```kotlin
service.register(scope, DurableAgentAttempt(attemptId = ..., caseId = ..., ...))
val claimed = service.claim(scope, ns, wf, step, attemptId, ownerToken, leaseTtlMs = 60_000)
service.transition(scope, ns, wf, step, attemptId, ownerToken, AgentAttemptStatus.STARTING)
service.transition(scope, ns, wf, step, attemptId, ownerToken, AgentAttemptStatus.RUNNING)
service.finalize(
    scope, ns, wf, step, attemptId, ownerToken,
    AgentAttemptStatus.SUCCEEDED,               // only from RUNNING / WAITING_HUMAN
    resultEvidenceId = "...", lastObservedEventId = "...",
)
```

Catch `AttemptClaimConflictException` on the claim race, `AttemptLeaseFencingException`
on a lost/expired/preempted lease at finalize, and `InvalidAttemptTransitionException`
for any state-machine violation — all carry a stable `errorCode` and structured
`details` rendered by `FactoryExceptionHandler`.

Run the tests:

```bash
cd factory-service && ./gradlew test --tests '*DurableAgentAttempt*' --tests '*AgentAttemptStateMachine*'
# or the whole module
pnpm nx test factory-service
```

Note: the claim's single-winner guarantee relies on the process-local `ReentrantLock`
(mirroring the embedded single-process lease repository). In a multi-process deployment
the Cypher CAS still prevents a double write, but the conflict semantics would need the
same revisit the lease repository would.
