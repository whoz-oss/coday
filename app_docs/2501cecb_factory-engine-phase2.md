# Factory engine-first Phase 2: automatic oracles and human resumption

## What changed

Phase 2 now connects the session DAG to automatic oracle evaluation and resumes DAG execution after a human checkpoint is answered.

- `SessionRunService` reads the session `workflowType`, filters the registered `OracleDefinition` catalogue using both `applicable.workflowTypes` and `applicable.stepIds` (empty lists act as wildcards), and runs every matching oracle after the primary capability succeeds. Any non-`SUCCEEDED` result, registry failure, or oracle exception changes the step to `failed`, so normal dependent-step blocking and terminal-state handling apply. Oracle evidence IDs are carried into the transition request and transition payload.
- `DefaultOracleEvidencePublisher` appends immutable `oracle-result` evidence through `WorkflowEvidenceRepository`. It maps execution outcomes to the policy vocabulary (`pass`/`fail`), records the trusted `factory-oracle` source and oracle facts, and uses a deterministic `(workflowId, stepId, oracleId)` evidence/idempotency key so replay does not create a duplicate.
- `WorkflowService.replyInteraction` now distinguishes the interaction optimistic-lock revision from the workflow state revision. After successfully closing a `checkpoint` interaction and publishing its SSE update, it invokes `SessionRunService.runSession` automatically. The repository root comes from the supplied `repoRoot`, AgentOS namespace resolution, or `Path.of(".")` as fallback. Non-DAG interaction types are not auto-resumed.

The persistence/JDBC layer and existing verification manifest loading are unchanged.

## Files carrying the change

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt` — oracle matching/execution, failure gating, and transition evidence propagation.
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/publisher/DefaultOracleEvidencePublisher.kt` — `oracle-result` evidence persistence, normalization, and replay idempotency.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/WorkflowService.kt` — checkpoint auto-resumption, repo-root resolution, and revision handling.
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/DefaultOracleEvidencePublisherTest.kt` — publisher contract, outcome normalization, and idempotency tests.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/SessionSequencerIntegrationTest.kt` — automatic human continuation, matching/non-matching oracle behavior, evidence creation, and failing-oracle dependent blocking.
- `specs/2501cecb_factory_engine_first_phase2.md` — implementation analysis and verification plan for the two sub-tasks.

## Verification

Run the factory-service test target, including `SessionSequencerIntegrationTest` and `DefaultOracleEvidencePublisherTest` (for example, `pnpm nx test factory-service`). The integration scenarios should show: a matching oracle creates one passing `oracle-result`; an inapplicable oracle creates none; a failed applicable oracle fails its step and blocks its dependent; and approving a checkpoint advances downstream steps without a second `/continue` or `runSession` call. The affected test, lint, and build commands in the project workflow can then be used for broader verification.
