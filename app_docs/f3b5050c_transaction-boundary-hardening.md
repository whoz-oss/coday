# Factory-service transaction-boundary hardening

## What changed

`factory-service` no longer holds one Neo4j transaction around the complete session DAG. `SessionRunService.runSession()` is now an orchestration method without `@Transactional`; it serializes runs for the same workflow with a process-local lock and persists step/projection work through short operations. Interrupted `running` steps are reset to `ready` before they can be claimed.

Capability execution is split so long-running work is outside a transaction:

- `CapabilityExecutionService.resolveAndRecord` uses short `REQUIRES_NEW` `TransactionTemplate` phases when a transaction manager is available.
- Agent steps reserve an attempt and issue the capability in phase 1, call the AgentOS/external resolver with no active transaction in phase 2, then terminalize the attempt and append `agent-turn` evidence in phase 3.
- Code and human resolver calls also happen before their recording transaction.
- External exceptions are converted to failed outcomes for agent turns; session-step failures are recovered in a fresh transaction containing failure evidence, the failed transition, and the failed step status.

Human replies are similarly decoupled in `WorkflowService`: interaction response, human-decision evidence, transition, and interaction closure are committed in a short isolated transaction before `resumeCheckpointSession` is called. A resumption exception therefore cannot roll back the already-committed reply. Existing SSE publication calls remain in the service code.

Conditional writes are now checked rather than ignored. Instance projection CAS failures, interaction closure CAS failures, and projection lifecycle CAS failures raise `REVISION_CONFLICT` instead of silently publishing or retaining stale state. Step claims use a graph-native CAS (`ready`/`pending` → `running`) that increments the revision and returns whether a node was updated; an unsuccessful claim skips external execution.

## Where it lives

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt` — non-transactional session orchestration, workflow lock, step claim, failure recovery, and strict instance CAS handling.
- `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt` — isolated capability phases and terminal attempt/evidence recording.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt` — isolated human-reply transaction and lifecycle/interaction CAS checks.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/WorkflowRepository.kt` — `claimStep` contract.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/Neo4jWorkflowRepository.kt` — repository implementation of the atomic claim.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/SpringDataNeo4jWorkflowRepositories.kt` — Cypher CAS query returning the update count.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/TransactionBoundaryIntegrationTest.kt` — Spring-context integration coverage for transaction isolation, failure recovery, concurrent execution, durable human replies, and rejected second claims.
- `specs/f3b5050c_refactor_transaction_boundaries.md` — the transaction-boundary refactoring plan and verification strategy recorded with the change.

The diff does not modify `Neo4jPersistenceConfiguration.kt` or `PersistenceConfigProperties.kt`; transaction managers are accepted as optional constructor dependencies and the implementation falls back to inline execution when absent, which supports the service tests that do not provide one.

## Verification

Run the factory-service integration tests through Nx:

```bash
pnpm nx test factory-service
```

The added `TransactionBoundaryIntegrationTest` specifically verifies that an agent capability observes no active transaction, that an execution failure is persisted as a failed step with evidence, that two concurrent calls execute a capability once, that a failing resumption leaves a closed human interaction and human-decision evidence durable, and that a second `ready` claim is rejected. For the repository-wide affected checks, use the project’s standard `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2` command (and the corresponding affected lint/build checks when needed).
