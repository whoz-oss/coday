# Plan — Durable execution attempt modelling (factory-service, Lot C)

## Goal

Model the durable execution attempt (`DurableAgentAttempt` / `AgentStepAttempt`) inside
`factory-service`, keyed on the identifiers supplied by the bridge, with:

- an **atomic claim** (Cypher CAS) so only one execution owns a step/attempt at a time,
- a **lease/fencing check at finalization** (`succeeded` / `failed` / …) that rejects a worker
  whose `ownerToken`/`leaseToken` has diverged or expired,
- a **strict state machine** where timeout / incomplete / unknown never yields `succeeded`,
- **idempotence by `attemptId`** (re-submission reuses the existing attempt, no duplicate).

This is additive persistence + domain work in
`factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/`. It reuses the exact
Neo4j SDN / Cypher style already in the repo (see the lease and result repositories).

## Hard constraints (do not violate)

- **Do NOT touch `agentos/`** — no change to the AgentOS core, its plugins or services.
- **Do NOT touch `AgentOsExecutionAdapter`** nor the Factory-side SSE client/adapter package
  (that work is done in parallel). Search first and stay out of those files.
- **Do NOT modify the existing result-path types** in this package
  (`AgentStepResultModels.kt`, `AgentStepResultValidation.kt`, `AgentStepResultService`,
  `AgentStepAttemptNode`/`AgentStepAttemptRepository`/`Neo4jAgentStepAttemptRepository`/
  `SpringDataNeo4jAgentStepAttemptRepository`, the result/outbox/capability repositories and
  their controllers). They are green today and are consumed by
  `AgentStepResultServiceIntegrationTest`, `AgentStepResultControllerHttpTest`,
  `OutboxDrain*` tests. This lot is **purely additive**: introduce a new
  `DurableAgentAttempt` aggregate alongside them so nothing existing changes behaviour.
- **`SessionRunService`**: leave untouched unless a concrete caller is required. It is **not**
  needed for the acceptance criteria (claim/finalize are exercised through the new service in
  integration tests). If the builder decides a wiring hook is genuinely required, add the
  minimal method and document why in the commit body — but the default is: no change.
- Never write scratch files into the repo tree; use `/tmp` if you must.

## Why a new aggregate rather than editing `AgentStepAttemptNode`

`AgentStepAttemptNode` is the mutable root of the *result* aggregate. Its `status` vocabulary
is `running` / `completed` (see `AgentStepResultServiceIntegrationTest`), it carries an
optimistic `revision`, and its `terminalize` Cypher is depended on by the result service. The
durable-execution state machine (`pending → claiming → starting → running → waiting_human →
{succeeded|failed|indeterminate|interrupted}`), the claim CAS and the lease-fencing finalize
are a different concern with a different lifecycle. Adding them as a **sibling aggregate**
(`DurableAgentAttempt`) keeps every existing test green while satisfying every acceptance
criterion. Reuse the shared `TenantScope`, `FactoryException`, composite-id and
process-local-lock + `REQUIRES_NEW` patterns already established by the lease repository.

## Reference patterns to copy (already in the repo)

- **Atomic transition Cypher** with `RETURN count(a)`:
  `agentattempt/persistence/SpringDataNeo4jAgentStepAttemptRepository.terminalize` and
  `lease/persistence/SpringDataNeo4jLeaseRepository`.
- **Serialised claim under embedded Neo4j**: `lease/persistence/Neo4jLeaseRepository` uses a
  process-local `ReentrantLock` (`claimLock`) held around a `PROPAGATION_REQUIRES_NEW`
  `TransactionTemplate`. Mirror this exactly for the claim CAS so the in-process test engine
  gives the same guarantee the former `FOR UPDATE SKIP LOCKED` gave.
- **Fencing rejection**: `Neo4jLeaseRepository.assertFencingToken` throwing
  `LeaseFencedException` (HTTP 409, code `LEASE_FENCED`). Model the finalize fencing on this.
- **Composite id + scope guard**: `AgentStepAttemptNode.compositeId(...)` /
  `Neo4jAgentStepAttemptRepository.scoped(...)`.
- **Error envelope**: extend `FactoryException` (see `AgentAttemptException` in
  `AgentStepResultModels.kt`) so `FactoryExceptionHandler` renders
  `{ "error": { code, message, details } }`.
- **Integration test base**: extend `io.whozoss.factory.Neo4jDomainIntegrationTest`
  (in-process Neo4j, no Docker; `scope`, `ORGANIZATION_ID`, `WORKSTREAM_ID` provided).
- **Concurrency test**: `workunit/WorkUnitLeaseConcurrencyTest` (ExecutorService +
  `CountDownLatch` start gate) and `lease/LeaseFencingAndExpiryTest`.

## Files to create

All under `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/`.
No-semicolons / single-quote-not-applicable (Kotlin) / 120-col / explicit types, matching the
surrounding Kotlin. KDoc every public type, as the neighbours do.

### 1. `domain/AgentAttemptStatus.kt`

Enum modelling the durable state machine, mirroring the shape of
`workunit/domain/WorkUnitState` (a `dbValue`, a `terminal` flag, `canTransitionTo`, an
`ALLOWED_TRANSITIONS` map, `fromDbValue`).

- Values + `dbValue`:
  `PENDING("pending")`, `CLAIMING("claiming")`, `STARTING("starting")`, `RUNNING("running")`,
  `WAITING_HUMAN("waiting_human")`, `SUCCEEDED("succeeded")`, `FAILED("failed")`,
  `INDETERMINATE("indeterminate")`, `INTERRUPTED("interrupted")`.
- `terminal`: true for `SUCCEEDED`, `FAILED`, `INDETERMINATE`, `INTERRUPTED`.
- `isSuccess`: true only for `SUCCEEDED`.
- `ALLOWED_TRANSITIONS`:
  - `PENDING → {CLAIMING, INTERRUPTED}`
  - `CLAIMING → {STARTING, FAILED, INDETERMINATE, INTERRUPTED}`
  - `STARTING → {RUNNING, FAILED, INDETERMINATE, INTERRUPTED}`
  - `RUNNING → {WAITING_HUMAN, SUCCEEDED, FAILED, INDETERMINATE, INTERRUPTED}`
  - `WAITING_HUMAN → {RUNNING, SUCCEEDED, FAILED, INDETERMINATE, INTERRUPTED}`
  - all four terminal states → `emptySet()`
  - **Key invariant to encode and comment: `SUCCEEDED` is reachable only from `RUNNING` or
    `WAITING_HUMAN`.** A timeout / lost-lease / unknown outcome must be expressed as
    `INDETERMINATE` (or `FAILED`/`INTERRUPTED`), never `SUCCEEDED`.
- `canTransitionTo(next)`: `next in ALLOWED_TRANSITIONS.getValue(this)`.
- Expose `transitions()` like `WorkUnitState` for the unit test.

### 2. `domain/DurableAgentAttempt.kt`

Domain record (data class) holding the bridge-supplied identity + lifecycle fields. All names
adhere to DECISION #1 (strict adoption of bridge identifiers):

- Identity / bridge: `attemptId`, `caseId`, `capabilityToken` (nullable), `ownerToken`
  (a.k.a. `leaseToken`; document the alias in KDoc), `turnCorrelation` (nullable),
  `commandId` (a.k.a. `idempotencyKey`; nullable), `namespaceId`, `workflowId`, `stepId`,
  `attemptNumber: Int`, `agentName`.
- Lifecycle: `status: AgentAttemptStatus`, `failureCode: String?`, `resultEvidenceId: String?`,
  `lastObservedEventId: String?`, `revision: Int`.
- Timestamps (`java.time.Instant`, nullable where not yet reached): `createdAt`, `startedAt?`,
  `updatedAt`, `completedAt?`.
- For the "ensemble dédupliqué borné" of `lastObservedEventId`: keep `lastObservedEventId` as
  the authoritative scalar high-water mark on the node; if a bounded dedup set is wanted, store
  it as a JSON list inside a `payload` string capped at a documented bound (e.g. 100 ids,
  drop-oldest). Keep it simple — the scalar high-water mark is sufficient for the acceptance
  criteria; only add the bounded set if a criterion below actually needs it (none does), so
  prefer the scalar and note the extension point in KDoc.
- Convenience: `fun canTransitionTo(next) = status.canTransitionTo(next)`.

### 3. `domain/DurableAgentAttemptExceptions.kt`

Error codes + exceptions extending `AgentAttemptException` (already in
`AgentStepResultModels.kt`, which extends `FactoryException`). Add to a new
`DurableAgentAttemptErrorCodes` object (do **not** edit `AgentAttemptErrorCodes`):

- `ATTEMPT_CLAIM_CONFLICT` → `AttemptClaimConflictException` (HTTP 409) — another execution
  already owns the attempt/step; the loser of a concurrent claim gets this.
- `ATTEMPT_LEASE_FENCED` → `AttemptLeaseFencingException` (HTTP 409) — finalize rejected
  because the caller's `ownerToken`/`leaseToken` diverged or the lease expired. This is the
  dedicated fencing error the prompt asks for (`LeaseFencingException` semantics).
- `ATTEMPT_INVALID_TRANSITION` → `InvalidAttemptTransitionException` (HTTP 409) — the requested
  `status` is not reachable from the current one under `ALLOWED_TRANSITIONS`.
- `ATTEMPT_NOT_FOUND` → `AttemptNotFoundException` (HTTP 404).
- (optional) `ATTEMPT_LEASE_EXPIRED` if you want to distinguish expiry from divergence in
  `details`; otherwise fold expiry into `ATTEMPT_LEASE_FENCED` with a `reason` detail.

Each carries a stable `errorCode` and structured `details` (e.g. `attemptId`, current vs
incoming `ownerToken`, `fromStatus`/`toStatus`) so tests assert on `errorCode`, never message.

### 4. `persistence/DurableAgentAttemptNode.kt`

`@Node("DurableAgentAttempt")` SDN projection, modelled on `AgentStepAttemptNode`:

- `@Id val id: String` = `compositeId(organizationId, workstreamId, namespaceId, workflowId,
  stepId, attemptId)` (encode with the same `|` separator convention). Because idempotence is
  by `attemptId` (DECISION #4), the `attemptId` is part of the key — a second `register` for
  the same `attemptId` resolves to the same node id.
- Plain properties for every `DurableAgentAttempt` field (store `status` as its `dbValue`
  string, `attemptNumber` as int, tokens/ids as strings, timestamps as `Instant`,
  `revision` as int, plus `organizationId`/`workstreamId` for the scope guard).
- `toDomain()` and `companion` `compositeId(...)` + `fromDomain(scope, attempt)` exactly like
  the neighbour node.

### 5. `persistence/SpringDataNeo4jDurableAgentAttemptRepository.kt`

`interface … : Neo4jRepository<DurableAgentAttemptNode, String>` declaring the graph-native
atomic statements (all return `count(a)` so the caller knows whether the CAS matched):

- **Idempotent register** — prefer a `MERGE` that only sets fields on create, so a re-register
  for the same `attemptId` never clobbers a live/valid state:
  ```
  MERGE (a:DurableAgentAttempt {id: $id})
  ON CREATE SET a.organizationId=$organizationId, a.workstreamId=$workstreamId,
                a.namespaceId=$namespaceId, a.workflowId=$workflowId, a.stepId=$stepId,
                a.attemptId=$attemptId, a.attemptNumber=$attemptNumber, a.agentName=$agentName,
                a.caseId=$caseId, a.capabilityToken=$capabilityToken, a.ownerToken=$ownerToken,
                a.turnCorrelation=$turnCorrelation, a.commandId=$commandId,
                a.status='pending', a.revision=1, a.createdAt=$now, a.updatedAt=$now
  RETURN a
  ```
  (Read-back the node after MERGE; `ON CREATE` absence means it already existed → treat as the
  idempotent replay path.)
- **Atomic claim (CAS)**:
  ```
  MATCH (a:DurableAgentAttempt {id: $id})
  WHERE a.status = 'pending' OR (a.status = 'claiming' AND a.ownerToken = $ownerToken)
  SET a.status='claiming', a.ownerToken=$ownerToken,
      a.leaseExpiresAt=$leaseExpiresAt, a.startedAt=coalesce(a.startedAt, $now),
      a.updatedAt=$now, a.revision=a.revision+1
  RETURN count(a) AS claimed
  ```
  The `OR (…ownerToken = $ownerToken)` branch makes a repeated claim by the *same* owner
  idempotent while a *different* owner (still `pending`? no — already `claiming`) matches
  nothing and gets `claimed = 0`.
- **Atomic finalize with fencing**:
  ```
  MATCH (a:DurableAgentAttempt {id: $id})
  WHERE a.ownerToken = $ownerToken
    AND NOT a.status IN ['succeeded','failed','indeterminate','interrupted']
  SET a.status=$status, a.failureCode=$failureCode, a.resultEvidenceId=$resultEvidenceId,
      a.completedAt=$now, a.updatedAt=$now, a.revision=a.revision+1
  RETURN count(a) AS finalized
  ```
  `finalized = 0` means either the ownerToken diverged (→ fencing) or the attempt is already
  terminal; the service disambiguates by reading the node back.
- **Generic transition** (non-terminal → non-terminal, e.g. `starting`, `running`,
  `waiting_human`) with the same `ownerToken` guard and `count(a)` return, for the
  intermediate steps the state machine allows.
- (optional) a lease-expiry-aware variant: extend the finalize `WHERE` with
  `AND (a.leaseExpiresAt IS NULL OR a.leaseExpiresAt > $now)` if you model an explicit lease
  deadline; otherwise expiry is represented by the ownerToken being rotated on re-claim.

Use the `${'$'}` escaping for `$` inside the Kotlin triple-quoted `@Query`, exactly as the
neighbouring repositories do.

### 6. `persistence/DurableAgentAttemptRepository.kt`

Port interface (mirror `AgentStepAttemptRepository` doc style): `register`, `find`, `claim`,
`transition`, `finalize`, all taking `TenantScope` + the identity coordinates.

### 7. `persistence/Neo4jDurableAgentAttemptRepository.kt`

`@Repository` (add `@Primary` only if it is the sole binding — check for conflicts) implementing
the port. Copy the lease repository's concurrency scaffolding:

- Constructor-inject `SpringDataNeo4jDurableAgentAttemptRepository` and
  `PlatformTransactionManager`.
- A private `claimLock = ReentrantLock()` and a `claimTransaction =
  TransactionTemplate(txManager){ propagationBehavior = PROPAGATION_REQUIRES_NEW }`.
- `claim(...)`: `claimLock.withLock { claimTransaction.execute { … } }` running the CAS query;
  if `claimed == 0` throw `AttemptClaimConflictException` with `details` = attempt coordinates.
- `finalize(...)`: validate `from.canTransitionTo(to)` in-domain first (throw
  `InvalidAttemptTransitionException` if not — this is the guard that makes it *impossible*
  for a timeout/unknown path to reach `succeeded`); run the fencing query; if `finalized == 0`,
  read the node back: `ownerToken` mismatch → `AttemptLeaseFencingException`; already-terminal
  with identical target status → return the existing record (idempotent finalize);
  otherwise → `InvalidAttemptTransitionException`.
- `register(...)`: MERGE, read back, return the (possibly pre-existing) record unchanged =
  idempotence by `attemptId`.
- `find(...)`/scope guard: copy `scoped(...)` from `Neo4jAgentStepAttemptRepository`.
- `transition(...)`: same domain guard + owner-guarded query for intermediate states.

### 8. `service/DurableAgentAttemptService.kt`

`@Service` transactional façade, modelled on `LeaseService` / `AgentStepResultService`:

- `register(scope, attempt, now)` → idempotent create, returns `DurableAgentAttempt`.
- `claim(scope, coordinates, ownerToken, leaseTtl, now)` → atomic claim, returns the claimed
  record or throws `AttemptClaimConflictException`.
- `transition(scope, coordinates, ownerToken, target, now)` → guarded intermediate transition.
- `finalize(scope, coordinates, ownerToken, target, failureCode?, resultEvidenceId?, now)` →
  fencing-checked finalize; enforces the state machine; `succeeded` requires the source status
  to be `RUNNING`/`WAITING_HUMAN` (delegated to `canTransitionTo`).
- `find(scope, coordinates)` read-only.
- Annotate mutating methods `@Transactional`, reads `@Transactional(readOnly = true)`.
- **Do not** wire this into `SessionRunService`. Leave a short KDoc note that the SSE/execution
  adapter (built in parallel) is the intended caller and is out of scope here.

## Tests to create

Under `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/`.

### A. `domain/AgentAttemptStateMachineTest.kt` (pure unit, no Spring)

Mirror the intent of `WorkUnitState` expectations:
1. Every allowed transition in `ALLOWED_TRANSITIONS` returns true from `canTransitionTo`.
2. `SUCCEEDED` is reachable **only** from `RUNNING` and `WAITING_HUMAN`; assert every other
   source → `SUCCEEDED` returns false (covers "timeout / incomplete / unknown never succeeds").
3. Terminal states allow no outgoing transition.
4. `isSuccess` true only for `SUCCEEDED`; `terminal` correct for the four terminal states.

### B. `DurableAgentAttemptClaimConcurrencyTest.kt` (`: Neo4jDomainIntegrationTest`)

Model on `WorkUnitLeaseConcurrencyTest`:
1. `register` an attempt (status `pending`).
2. N threads (ExecutorService + `CountDownLatch` start gate) each call
   `service.claim(...)` with a **distinct** `ownerToken`.
3. Assert exactly one call succeeds; every other throws `AttemptClaimConflictException`
   (assert `errorCode == "ATTEMPT_CLAIM_CONFLICT"`).
4. Assert the persisted node's `ownerToken` equals the winner's and `status == claiming`.
5. Add a simple test: the *same* owner claiming twice is idempotent (second claim returns
   without error, no state regression).

### C. `DurableAgentAttemptFencingTest.kt` (`: Neo4jDomainIntegrationTest`)

Model on `LeaseFencingAndExpiryTest`:
1. `register` + `claim` with `ownerToken = "owner-a"`, advance `claiming → starting → running`.
2. Finalize with a **divergent** `ownerToken` (`"owner-b"`, simulating a lost/preempted lease)
   → `AttemptLeaseFencingException` (assert `errorCode == "ATTEMPT_LEASE_FENCED"`); assert the
   node status is unchanged (still `running`).
3. Finalize with the **correct** owner and target `SUCCEEDED` from `RUNNING` → succeeds; node
   `status == succeeded`, `completedAt` set, `resultEvidenceId` persisted.
4. Attempt an illegal finalize, e.g. `PENDING → SUCCEEDED` or `STARTING → SUCCEEDED`
   → `InvalidAttemptTransitionException` (`ATTEMPT_INVALID_TRANSITION`); this is the
   "timeout/incomplete never succeeds" guard at the persistence boundary.
5. Idempotence: `register` the same `attemptId` twice → only one node exists (query count == 1)
   and the second call returns the existing record without altering a valid state. If you kept
   a re-claim path, also assert a finalize replay to the same terminal status is idempotent.

Use `neo4jDriver` / the repositories via `@Autowired`, and the `scope` from the base class.
Follow the existing style: `assertThat(...).hasFieldOrPropertyWithValue("errorCode", "...")`.

## Wiring / config

- The new `@Service` and `@Repository` are component-scanned like their neighbours; no manual
  bean config expected. Verify no existing `@Bean`/`@Primary` conflict for the new repository
  (grep the composition-root config under `factory/config`); add `@Primary` only if a second
  binding of the port exists.
- No Flyway / migration changes — this is Neo4j graph, schema-less (DECISION: do not touch
  migrations, per project rules).

## How to verify

Run from the repo root (the factory runs affected tests automatically; run locally only to
debug):

- Targeted module tests:
  `pnpm nx test factory-service`
  or the Gradle equivalent the module uses:
  `cd factory-service && ./gradlew test`
- Single new test while iterating, e.g.:
  `cd factory-service && ./gradlew test --tests '*DurableAgentAttempt*'`
- Compile / build gate: `pnpm nx build factory-service` (or `./gradlew build`).
- Judge success by exit status, not by scanning output for the word "error".

Acceptance mapping:
1. Atomic claim → **Test B**.
2. Finalize fencing → **Test C** steps 1–2.
3. State machine (no success on timeout/unknown) → **Test A** + **Test C** step 4.
4. Idempotence by `attemptId` → **Test C** step 5 (+ same-owner re-claim in Test B).
5. Whole module compiles and `factory-service` test suite is green.

## Out-of-scope reminder

`agentos/`, `AgentOsExecutionAdapter`, the Factory SSE client/adapter package, the existing
result-path types and their tests, and `SessionRunService` (unless a hook is proven necessary
and then only minimally, documented in the commit body).
