# Plan: Finalize AgentAttempt, Outbox, and Idempotency Neo4j Migration and Commit

## Overview
Phase 2 Neo4j migration for `AGENTATTEMPT`, `OUTBOX`, and `IDEMPOTENCY` is code-complete and validated by integration tests (`cd agentos && ./gradlew --no-daemon :agentos-service:test`).
The previous execution failed the gate `diff_matches_claims` because deleted JDBC repository files (`JdbcAgentStepAttemptRepository.kt`, `JdbcAgentStepResultRepository.kt`, `JdbcIdempotencyRepository.kt`) were omitted or untracked properly in git / claims manifest.

This plan details the steps required to verify the replacements, check schema configuration and tests, ensure clean deletion and tracking of obsolete JDBC files, run tests, and create a single clean git commit for Phase 2.

## Scope & File Changes

### Deleted Files (JDBC Implementations)
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcAgentStepAttemptRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcAgentStepResultRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcIdempotencyRepository.kt`

### Added Files (Neo4j Implementations & SDN Repositories)
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/AgentStepAttemptNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/AgentStepResultNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/IdempotencyRecordNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/OutboxEventNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/ResultCapabilityNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jAgentStepAttemptRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jAgentStepResultRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/Neo4jIdempotencyRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jAgentStepAttemptRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jAgentStepResultRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jIdempotencyRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jOutboxRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/SpringDataNeo4jResultCapabilityRepository.kt`

### Modified Files (Service, Config, & Tests)
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/OutboxDrainService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultControllerHttpTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultServiceIntegrationTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/OutboxDrainServiceIntegrationTest.kt`

## Verification Steps

### Task 1: Verify JDBC File Deletions & References
1. Confirm `JdbcAgentStepAttemptRepository.kt`, `JdbcAgentStepResultRepository.kt`, `JdbcIdempotencyRepository.kt`, and `JdbcOracleExecutionRepository.kt` do not exist on disk.
2. Run `git status` to verify git tracks their deletion (`deleted:` status).
3. Search codebase for any remaining imports/usages of the deleted `Jdbc*` classes in `factory-service`.

### Task 2: Verify Neo4j Persistence Integration
1. Verify `Neo4jAgentStepAttemptRepository`, `Neo4jAgentStepResultRepository`, and `Neo4jIdempotencyRepository` implement their respective domain interface ports (`AgentStepAttemptRepository`, `AgentStepResultRepository`, `IdempotencyRepository`).
2. Verify `OutboxDrainService` uses atomic Cypher claiming via `SpringDataNeo4jOutboxRepository`.

### Task 3: Verify Schema Initializer & Persistence Configuration
1. Inspect `Neo4jSchemaInitializer.kt` to ensure constraints and indexes are created for:
   - `AgentStepAttempt` (constraint on `id`, index on `organizationId`, `workstreamId`, `status`)
   - `AgentStepResult` (constraint on `id`, index on tenant + attempt scope)
   - `ResultCapability` (constraint on `id`, index on `tokenHash`)
   - `OutboxEvent` (constraint on `id`, index on `organizationId`, `status`, `createdAt`)
   - `IdempotencyRecord` (constraint on `id`)
2. Inspect `Neo4jPersistenceConfiguration.kt` to ensure `@EnableNeo4jRepositories` includes `io.whozoss.factory.agentattempt.persistence`.

### Task 4: Run Tests
1. Run `./gradlew --no-daemon :agentos-service:test` from `agentos/` directory.
2. Confirm clean execution with 0 failures (`BUILD SUCCESSFUL`).

### Task 5: Commit Phase 2 Changes
1. Stage all added, modified, and deleted files using `git add -A` or explicit git paths.
2. Commit with conventional commit message:
   `feat(factory): finalize Neo4j migration for AgentAttempt, Outbox, and Idempotency`
3. Verify `git status` reports working tree clean.

## Execution Command for Builder
```bash
cd /work/app/agentos && ./gradlew --no-daemon :agentos-service:test
```
