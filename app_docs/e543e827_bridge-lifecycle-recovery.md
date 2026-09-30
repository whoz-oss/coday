# AgentOS–Factory bridge lifecycle completion

## What changed

Step 8 adds lifecycle handling around durable AgentOS attempts in `factory-service`:

- **Startup recovery:** `BridgeRecoveryWorker` listens for `ApplicationReadyEvent`, scans bounded non-terminal attempts across tenant scopes/namespaces, reconciles each AgentOS case, and uses a fresh lease before changing Factory state. Terminal AgentOS snapshots are finalized without starting another turn; already-started attempts resume observation; a turn is re-driven only for a still-`PENDING` attempt with a persisted brief. Lease conflicts are reported rather than stealing a live lease. Recovery can also persist `agent-result` evidence.
- **Persistent command identity:** `DurableAgentAttempt.brief` is stored with the attempt. `CapabilityExecutionService` compares a replayed brief with the stored brief for the same `attemptId` and raises `IdempotencyKeyCollisionException` on a payload mismatch. Matching terminal attempts continue through the existing idempotent path; no second turn is dispatched by the added recovery logic.
- **Observation timeout escalation:** `ObservationEscalationPolicy` applies snapshot reconciliation, bounded SSE reconnection, optional kill, and post-kill reconciliation in that order. It returns a terminal verdict only when one of those operations provides proof; otherwise it keeps an `Indeterminate` verdict with the escalation trail and never infers success.
- **Explicit cancellation:** `BridgeCancellationService` implements `requestCancel`: it issues `adapter.interrupt`, performs a best-effort post-interrupt reconciliation, then performs a revision-fenced durable transition to `INTERRUPTED`. Repeating cancellation of an already interrupted attempt is idempotent, while stale revisions and other terminal states are rejected. Closing an SSE stream remains observation-only.
- **HTTP route:** `WorkflowController` exposes `POST /api/factory/workflows/{workflowId}/attempts/{attemptId}/cancel`. The body requires numeric `expectedRevision` and accepts `namespaceId` and `reason`; the route returns the attempt status, new revision, idempotency flag, and reconciled verdict. When the AgentOS adapter is disabled, it returns `503 BRIDGE_CANCELLATION_UNAVAILABLE`.

The change does not modify AgentOS core or the legacy polling client. The repository diff contains no outbox or bridge-plugin source changes; the concrete idempotency reinforcement shown here is at durable-attempt/capability execution and recovery boundaries.

## Where it lives

- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/BridgeRecoveryWorker.kt` — boot sweep, lease claiming, reconcile/resume/redrive decisions, and evidence persistence.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/BridgeCancellationService.kt` — explicit cancellation command and adapter interaction.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/DurableAgentAttemptService.kt` — service operations for scoped lookup, non-terminal scans, and cancellation.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/{DurableAgentAttemptRepository.kt,Neo4jDurableAgentAttemptRepository.kt,SpringDataNeo4jDurableAgentAttemptRepository.kt,DurableAgentAttemptNode.kt}` — whole-graph scan, workflow/attempt lookup, revision-fenced cancellation CAS, and persistence of `brief`.
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttempt.kt` — persisted turn brief used to safely re-drive only an unaccepted command.
- `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/ObservationEscalationPolicy.kt` — deterministic timeout escalation chain.
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt` — brief capture/comparison and escalation integration for indeterminate observations.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/web/WorkflowController.kt` — cancellation endpoint and request validation.

## Verification

The added coverage is in:

- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/BridgeRecoveryWorkerTest.kt`: restart during a turn resumes observation without `startTurn`, terminal-result recovery uses one snapshot, live leases are not stolen, only a never-claimed attempt is re-driven, and stale workers receive `ATTEMPT_LEASE_FENCED`.
- `factory-service/src/test/kotlin/io/whozoss/factory/adapter/agentos/ObservationEscalationPolicyTest.kt`: snapshot proof, SSE reconnect proof, kill/post-kill proof, insufficient-proof indeterminacy, and kill-disabled behavior.
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/BridgeCancellationServiceTest.kt`: explicit interruption, revision conflict, idempotent replay, and the fact that observation disconnect alone does not cancel.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/DurableAgentOsBridgeIntegrationTest.kt`: same-`attemptId` payload collision does not start a turn.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowControllerHttpTest.kt`: disabled-bridge HTTP response.

Run the factory-service acceptance suite with `cd factory-service && ./gradlew test`. For a manual cancellation call, send for example:

```http
POST /api/factory/workflows/<workflowId>/attempts/<attemptId>/cancel
Content-Type: application/json

{"namespaceId":"<namespaceId>","expectedRevision":<currentRevision>,"reason":"User requested cancellation"}
```

The implementation notes and architectural boundaries are also recorded in `specs/e543e827_agentos_factory_bridge_lifecycle.md`.
