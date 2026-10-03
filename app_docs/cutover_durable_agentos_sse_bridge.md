# Cutover — Durable SSE AgentOS bridge as the primary execution path

**Status:** done (final cutover, Step 5)
**Scope:** `factory-service` (`io.whozoss.factory.capability`, `io.whozoss.factory.adapter.agentos`,
`io.whozoss.factory.agentattempt`)

This note records the cutover of the durable AgentOS SSE bridge
(`AgentOsExecutionAdapter` + `DurableAgentAttemptService`) into the **primary,
non-optional** execution driver of `CapabilityExecutionService`, and the demotion
of the historical HTTP polling driver (`HttpAgentOsProxyClient` /
`AgentOsAgentTurnCapability`) to an explicit, configuration-gated fallback.

---

## 1. What changed

### 1.1 `CapabilityExecutionService` constructor is now mandatory on the bridge

`DurableAgentAttemptService` and `AgentOsExecutionAdapter` are now **required,
non-null constructor parameters** of `CapabilityExecutionService`. They are no
longer optional (`? = null`). The legacy polling turn driver is still compiled in
(`resolveAgentViaPolling`) but is no longer reachable by default.

```kotlin
class CapabilityExecutionService(
    resolver: CapabilityResolver,
    workflowRepository: WorkflowRepository,
    evidenceRepository: WorkflowEvidenceRepository,
    interactionRepository: HumanInteractionRepository,
    attemptRepository: AgentStepAttemptRepository,
    durableAgentAttemptService: DurableAgentAttemptService,   // mandatory
    agentOsExecutionAdapter: AgentOsExecutionAdapter,         // mandatory
    agentStepResultService: AgentStepResultService? = null,
    transactionManager: PlatformTransactionManager? = null,
    agentObservationTimeoutMs: Long = 600_000L,
    agentLeaseTtlMs: Long? = 3_600_000L,
    observationEscalation: ObservationEscalationPolicy = ObservationEscalationPolicy(),
    agentOsAdapterProperties: AgentOsAdapterProperties = AgentOsAdapterProperties(),
)
```

`resolveAgent` dispatches on `agentOsAdapterProperties.enabled`:

- `true` (default) → `resolveAgentViaAdapter` — the durable SSE bridge: short
  `REQUIRES_NEW` claim transaction, **untransacted** remote turn, short
  `REQUIRES_NEW` finalize transaction with durable `agent-result` evidence.
- `false` → `resolveAgentViaPolling` — the legacy polling turn driver
  (troubleshooting fallback only).

All callers and test instantiations were updated accordingly:

- test doubles inject an explicit `AgentOsAdapterProperties(enabled = false)`
  when they exercise the legacy polling path (`CapabilityExecutionIntegrationTest`,
  `CapabilityExecutionCapabilityIssuanceTest`, `TransactionBoundaryIntegrationTest`,
  `SessionSequencerIntegrationTest`, `SessionDefinitionImportIntegrationTest`);
- the bridge integration tests inject the real `DurableAgentAttemptService` and a
  stateful fake `AgentOsExecutionAdapter` (`DurableAgentOsBridgeIntegrationTest`).

### 1.2 Default SSE is enabled

| Setting | Before | After |
|---|---|---|
| `factory.adapter.agentos.enabled` default | `false` | **`true`** (`AgentOsAdapterProperties`) |
| `agentOsExecutionAdapter` bean | conditionally created only when `enabled=true` | **always created** (`AgentOsAdapterConfiguration`) |
| `BridgeRecoveryWorker` / `BridgeCancellationService` | only when `enabled=true` | by default (`matchIfMissing=true`), absent when `enabled=false` |

The adapter bean is created unconditionally so the mandatory dependency can
always be satisfied; `factory.adapter.agentos.enabled` now selects the **driver**
rather than gating bean existence.

### 1.3 Behavioural invariants enforced

- **Nominal execution is SSE, not polling.** Polling is no longer the primary
  observation mechanism.
- **No Neo4j transaction is open during a remote AgentOS turn.** Phase 2 runs
  with no transaction; only Phase 1 (claim) and Phase 3 (finalize/evidence) are
  short `REQUIRES_NEW` transactions.
- **Outage / restart never duplicates work.** Attempts are keyed by the stable
  `attemptId = "{workflowId}#{stepId}"` and the stable `caseId`;
  `startTurn` runs at most once per attempt; `eventId` dedup uses a persistent
  high-water mark.
- **Strict phase gate.** Step B starts only after step A's durable `agent-result`
  evidence is committed and A's attempt is terminal `succeeded`. B's brief is
  sourced from A's **persisted** outputs (`facts["outputs"]`), never from a raw
  message.
- **No inference from silence.** `IDLE` without structured output, `ERROR`,
  `KILLED`, observation timeouts and exhausted reconnection budgets are
  `Indeterminate`/`Failed` — never `Succeeded`.
- **AgentOS core is untouched** (`caseEvent`/`caseFlow` unchanged); idempotency is
  owned by the Factory side (`attemptId`).

---

## 2. How to fall back to polling (troubleshooting only)

Set the property (or env var through the standard Spring relaxed binding):

```yaml
factory:
  adapter:
    agentos:
      enabled: false      # -> legacy HttpAgentOsProxyClient polling driver
```

Effects:

- `CapabilityExecutionService.resolveAgent` drives `agent` steps through
  `resolveAgentViaPolling` (the historical `agent_step_attempts` +
  `AgentOsAgentTurnCapability` path) instead of the durable SSE bridge.
- `BridgeRecoveryWorker` and `BridgeCancellationService` beans are not created,
  so `POST …/attempts/{attemptId}/cancel` answers `503 BRIDGE_CANCELLATION_UNAVAILABLE`.
- The `AgentOsExecutionAdapter` bean still exists (mandatory dependency) but is
  never invoked.

Re-enable by removing the property (default `true`) or setting it to `true`.

---

## 3. Acceptance test report — the 20 criteria

All criteria are verified and green. The new/extended tests added by this cutover
live in `DurableAgentOsBridgeIntegrationTest`; the SSE-protocol criteria are
verified by the dedicated adapter/SSE test classes.

| # | Criterion | Verified by |
|---|---|---|
| 1 | Blocked A ⇒ B never starts | `DurableAgentOsBridgeIntegrationTest.a blocked dependency prevents A and B from ever starting an agent turn` |
| 2 | A completes with identifiable output ⇒ B starts once, receives exact output | `DurableAgentOsBridgeIntegrationTest.a successful agent step releases B which receives exactly A's persisted output` |
| 3 | A fails ⇒ B blocked | `DurableAgentOsBridgeIntegrationTest.a failed agent step blocks B and B never starts`; `SessionSequencerIntegrationTest.a failed agent turn fails the step and blocks its dependents` |
| 4 | A waiting human ⇒ B not started | `DurableAgentOsBridgeIntegrationTest.an agent step waiting for a human suspends the session and B never starts` |
| 5 | Concurrent requests ⇒ single attempt owns execution | `DurableAgentOsBridgeIntegrationTest.two concurrent executions yield exactly one owning attempt and one turn`; `DurableAgentAttemptClaimConcurrencyTest.concurrent claims on the same attempt yield exactly one owner`; `TransactionBoundaryIntegrationTest.two concurrent runs execute the external capability exactly once` |
| 6 | SSE drop during A + reconnect ⇒ no duplicates, result found | `AgentOsSseClientTest.a dropped connection reconnects, reconciles over REST and never double-processes an event`; `DefaultAgentOsExecutionAdapterTest.observeTurn falls back to the REST reconciliation after a connection drop` |
| 7 | Factory restart during A ⇒ observation resumed, work not recreated | `BridgeRecoveryWorkerTest.restart during a turn resumes observation without a second turn` |
| 8 | Connection after A finished ⇒ replay/snapshot enables finalization | `AgentOsSseClientTest.connection after completion replays the full history and derives the final verdict`; `BridgeRecoveryWorkerTest.crash after the AgentOS result but before the Factory commit finalizes on one reconciliation` |
| 9 | Duplicate event ⇒ single finalization | `AgentOsSseClientTest.a duplicate event in the stream is processed exactly once`; `HighWaterMarkTest.an event id is recorded once and duplicates are rejected` |
| 10 | Stale event from a previous turn ⇒ current attempt unchanged | `AgentOsSseClientTest.frames of a foreign case are strictly ignored`; `AgentOsSseClientTest.a warm high-water mark dedups the replayed prefix without reprocessing`; `HighWaterMarkTest.a replay of earlier events is fully covered by the mark` |
| 11 | Crash after case creation, before local persistence ⇒ case found & reattached | `DurableAgentOsBridgeIntegrationTest.a case created before local persistence is recovered and reattached on replay` |
| 12 | Crash after message acceptance, before HTTP response ⇒ no second turn | `DurableAgentOsBridgeIntegrationTest.a crash after the message was accepted never sends a second turn` |
| 13 | Crash after AgentOS result, before Factory commit ⇒ reconciliation, single finalization | `BridgeRecoveryWorkerTest.crash after the AgentOS result but before the Factory commit finalizes on one reconciliation` |
| 14 | Worker lost its lease attempted finalize ⇒ fencing rejection | `DurableAgentAttemptFencingTest.finalize with a divergent lease token is fenced and leaves the attempt unchanged`; `DurableAgentAttemptFencingTest.a preempted worker whose lease expired is fenced out of finalization`; `BridgeRecoveryWorkerTest.a worker that lost its lease is fenced out of finalization` |
| 15 | IDLE with unanswered question ⇒ WaitingHuman | `VerdictDeriverTest.IDLE with an unanswered question is WaitingHuman with the question id as ref`; `AgentOsSseClientTest.IDLE with an unanswered question is WaitingHuman`; `DefaultAgentOsExecutionAdapterTest.reconcile derives WaitingHuman from an IDLE case with an unanswered question` |
| 16 | IDLE without structured output ⇒ Indeterminate | `VerdictDeriverTest.IDLE without question and without structured output is Indeterminate - never Succeeded`; `AgentOsSseClientTest.IDLE without question and without structured output is Indeterminate - never Succeeded` |
| 17 | ERROR / KILLED / timeout ⇒ never implicit success | `VerdictDeriverTest.ERROR is Failed and never Succeeded`; `VerdictDeriverTest.KILLED is Failed and never Succeeded`; `AgentOsSseClientTest.an exhausted reconnection budget is Indeterminate - never a verdict by silence`; `AgentOsSseClientTest.a stalled stream without heartbeat is treated as dropped and reconciled` |
| 18 | Valid structured output ⇒ evidence + outputs persisted before B activation | `DurableAgentOsBridgeIntegrationTest.B starts only after A's agent-result evidence and successful attempt are durably committed`; `DurableAgentOsBridgeIntegrationTest.a successful agent step releases B which receives exactly A's persisted output` |
| 19 | Turn longer than Neo4j timeout ⇒ completes, zero open transaction | `DurableAgentOsBridgeIntegrationTest.a long remote turn runs with no open transaction and finalizes normally`; `TransactionBoundaryIntegrationTest.external agent execution runs with no active transaction and completes` |
| 20 | Finalization failure ⇒ remote result preserved/reconcilable, no false cockpit notification | `DurableAgentOsBridgeIntegrationTest.a finalization failure preserves the remote result for reconciliation and never reports success` |

### How to run the acceptance tests

From `factory-service/`:

```bash
./gradlew compileKotlin compileTestKotlin      # compilation gate
./gradlew test                                  # full unit + integration suite
./gradlew test --tests "io.whozoss.factory.workflow.DurableAgentOsBridgeIntegrationTest"
```

The bridge integration tests run against the real Spring beans and the in-process
embedded Neo4j harness (no Docker, no external AgentOS): the remote boundary is a
stateful fake `AgentOsExecutionAdapter`, so the claim/observe/finalize
orchestration is exercised exactly as in production.
