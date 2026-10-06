# PostgreSQL agent-step and oracle adapters

## What changed

The factory now has SQL persistence adapters for agent-step attempts, agent-step results, and oracle execution definitions, plus an offline cross-adapter conformance suite. The adapters use the existing tenant-scoped SQL client and transaction helper, persist domain records as JSONB payloads, and apply the same domain validation and error codes as the filesystem implementations.

### Agent-step attempts

`factory/src/adapters/persistence/sql/sql-agent-step-attempt-repository.ts` implements `AgentStepAttemptRepository` over `agent_step_attempts` and `agent_step_attempt_events`. Organization and workstream are fixed when the repository is created, defaulting to `default`; `storageId` is used as the step scope. `append()` validates namespace, immutable identity fields, initial `starting` status, and legal status transitions. The aggregate row is revisioned and updated optimistically, while every append creates an event row. `list()` returns the event payloads in creation order. The file also exports `createSqlAgentStepAttemptRepository()`.

### Agent-step results

`factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts` implements `AgentStepResultRepository` over `result_capabilities`, `agent_step_results`, and the V4 `outbox_events` table. Capability issuance validates safe identities and the SHA-256 brief hash, stores only a token digest, detects duplicate or conflicting issuance, and returns the clear token with its expiry. Submission validates the business payload, resolves the capability by hashed token, checks observed identity, supports idempotent replay, and reports semantic collisions, invalid capabilities, mismatches, and expiry through the established result codes.

A new submission is transactional: the result row, terminal attempt status/revision, and `result_submitted` outbox event are written in one unit of work. The adapter also provides `getByAttempt()`, `list()`, and `createSqlAgentStepResultRepository()`.

### Oracle execution support

`factory/src/adapters/persistence/sql/sql-oracle-execution-repository.ts` implements `OracleExecutionRepository` by reading validated oracle definitions from `oracle_executions` JSONB payloads, with tenant filtering and `list()` de-duplication. It exports `createSqlOracleExecutionRepository()` and adds `terminalize()`. Terminalization updates the execution revision/status and marks its linked artifact `available` in the same transaction, so an artifact update failure rolls back the execution transition.

## Verification

Run the standalone conformance suite from the repository root:

```bash
node factory/tests/test-conformance-agent-step-oracle.mjs
```

`factory/tests/test-conformance-agent-step-oracle.mjs` bundles the TypeScript SQL adapter sources in memory so it can run directly under Node without changing generated files. It executes shared lifecycle, capability, submission, expiry, and error-code parity cases against filesystem and in-memory SQL adapters. SQL-specific cases verify result/attempt/outbox rollback and oracle artifact publication/rollback. Exit code `0` indicates all cases passed; failures produce exit code `1`.

The implementation plan and detailed table/behavior mapping are recorded in `specs/341d8d2f_postgres_adapters_conformance_suite.md`.

## Files

- `factory/src/adapters/persistence/sql/sql-agent-step-attempt-repository.ts`
- `factory/src/adapters/persistence/sql/sql-agent-step-result-repository.ts`
- `factory/src/adapters/persistence/sql/sql-oracle-execution-repository.ts`
- `factory/tests/test-conformance-agent-step-oracle.mjs`
- `specs/341d8d2f_postgres_adapters_conformance_suite.md`
