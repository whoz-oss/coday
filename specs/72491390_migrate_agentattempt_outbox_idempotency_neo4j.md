# Implementation Plan - Migrate AgentAttempt, Outbox, and Idempotency to Embedded Neo4j & Oracle Cleanup

## Context & Objectives
This task completes Phase 2 migration of persistence aggregates from PostgreSQL/JDBC to embedded Neo4j in `factory-service`.
The aggregates to migrate are:
- `AGENTATTEMPT` (`AgentStepAttempt`)
- `RESULT` (`AgentStepResult`) & `CAPABILITY` (`AgentStepResultCapability`)
- `OUTBOX` (`OutboxEvent`) & `OutboxDrainService`
- `IDEMPOTENCY` (`IdempotencyRecord`)

Additionally:
- Clean up duplicate/legacy `JdbcOracleExecutionRepository.kt` (leftover from Phase 1 if present; verified absent, but checked).
- Replace JDBC implementations (`JdbcAgentStepAttemptRepository`, `JdbcAgentStepResultRepository`, `JdbcIdempotencyRepository`) with Neo4j-backed repositories.
- Adapt `OutboxDrainService` for a mono-writer embedded Cypher model replacing SQL `FOR UPDATE SKIP LOCKED`.
- Update `Neo4jSchemaInitializer.kt` with Cypher constraints/indexes for the new node labels (`AgentStepAttempt`, `AgentStepResult`, `ResultCapability`, `OutboxEvent`, `IdempotencyRecord`).
- Update `Neo4jPersistenceConfiguration.kt` repository package scan list if necessary (`io.whozoss/factory/agentattempt/persistence`).
- Update/add integration tests so that agentattempt, outbox, and idempotency tests run against the embedded Neo4j test harness (`Neo4jDomainIntegrationTest` / `Neo4jIntegrationTest`) without requiring Docker/PostgreSQL.

---

## Technical Design & Architecture

### Node Models (`@Node`)

1. **`AgentStepAttemptNode`** (`@Node("AgentStepAttempt")`)
   - `id`: Composite key `"$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId|$attemptId"`
   - `organizationId`: String
   - `workstreamId`: String
   - `namespaceId`: String
   - `workflowId`: String
   - `stepId`: String
   - `attemptId`: String
   - `agentId`: String
   - `status`: String
   - `revision`: Int
   - `payload`: String (JSON)
   - `createdAt`: Instant
   - `updatedAt`: Instant

2. **`AgentStepResultNode`** (`@Node("AgentStepResult")`)
   - `id`: Composite key `"$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId|$attemptId|$resultId"`
   - `organizationId`: String
   - `workstreamId`: String
   - `namespaceId`: String
   - `workflowId`: String
   - `stepId`: String
   - `attemptId`: String
   - `resultId`: String
   - `resultStatus`: String
   - `semanticSignature`: String?
   - `payload`: String (JSON)
   - `createdAt`: Instant

3. **`ResultCapabilityNode`** (`@Node("ResultCapability")`)
   - `id`: Composite key `"$organizationId|$workstreamId|$capabilityId"`
   - `organizationId`: String
   - `workstreamId`: String
   - `namespaceId`: String
   - `workflowId`: String
   - `stepId`: String
   - `attemptId`: String
   - `resultId`: String
   - `capabilityId`: String
   - `capabilityType`: String
   - `tokenHash`: String? (Indexed/extracted property for fast constant-time lookup)
   - `payload`: String (JSON)
   - `createdAt`: Instant

4. **`OutboxEventNode`** (`@Node("OutboxEvent")`)
   - `id`: Composite key `"$organizationId|$id"`
   - `organizationId`: String
   - `id`: String
   - `workstreamId`: String
   - `eventType`: String
   - `payload`: String (JSON)
   - `status`: String (`pending`, `dispatched`, `failed`)
   - `attempts`: Int
   - `createdAt`: Instant
   - `dispatchedAt`: Instant?

5. **`IdempotencyRecordNode`** (`@Node("IdempotencyRecord")`)
   - `id`: Composite key `"$organizationId|$idempotencyKey"`
   - `organizationId`: String
   - `idempotencyKey`: String
   - `workstreamId`: String
   - `requestHash`: String
   - `resourceRef`: String
   - `status`: String
   - `responsePayload`: String (JSON)
   - `createdAt`: Instant

---

### Spring Data Neo4j Repositories

1. **`SpringDataNeo4jAgentStepAttemptRepository`**
   - `Neo4jRepository<AgentStepAttemptNode, String>`
   - `findScopeAttempt(...)`: Cypher query matching scope and 4-tuple key coordinates.
   - `terminalize(...)`: Atomic Cypher update updating status, incrementing `revision` and setting `updatedAt`.

2. **`SpringDataNeo4jAgentStepResultRepository`**
   - `Neo4jRepository<AgentStepResultNode, String>`
   - Cypher queries to find by attempt coordinates, find existing capabilities, etc.

3. **`SpringDataNeo4jResultCapabilityRepository`**
   - `Neo4jRepository<ResultCapabilityNode, String>`
   - Cypher query `findByTokenHash(organizationId, workstreamId, tokenHash)` for fast, indexed lookup.

4. **`SpringDataNeo4jOutboxRepository`**
   - `Neo4jRepository<OutboxEventNode, String>`
   - `findPendingOrganizations()`: `MATCH (o:OutboxEvent {status: 'pending'}) RETURN DISTINCT o.organizationId`
   - `findPendingEvents(organizationId, limit)`: Cypher matching `OutboxEvent` with status `pending` ordered by `createdAt ASC` limited by parameter.
   - `markDispatched(...)`, `markFailed(...)`: Cypher updates.

5. **`SpringDataNeo4jIdempotencyRepository`**
   - `Neo4jRepository<IdempotencyRecordNode, String>`
   - `findByScopeAndKey(organizationId, idempotencyKey)`

---

### Domain Repositories & Adapters

1. **`Neo4jAgentStepAttemptRepository`** (implements `AgentStepAttemptRepository`)
   - Delegates to `SpringDataNeo4jAgentStepAttemptRepository`.
   - Replaces `JdbcAgentStepAttemptRepository`.

2. **`Neo4jAgentStepResultRepository`** (implements `AgentStepResultRepository`)
   - Delegates to `SpringDataNeo4jAgentStepResultRepository`, `SpringDataNeo4jResultCapabilityRepository`, `Neo4jOutboxRepository` (or SDN Outbox repo), and `AgentStepAttemptRepository`.
   - Encapsulates `issue` and `submit` operations. In `submit()`, handles reservation updating/creation, attempt terminalization, and outbox event creation.
   - Replaces `JdbcAgentStepResultRepository`.

3. **`Neo4jIdempotencyRepository`** (implements `IdempotencyRepository`)
   - Delegates to `SpringDataNeo4jIdempotencyRepository`.
   - Replaces `JdbcIdempotencyRepository`.

4. **`OutboxDrainService` Adaptation**
   - Refactored to consume Spring Data / Neo4j repository or driver queries instead of `NamedParameterJdbcTemplate` with SQL `FOR UPDATE SKIP LOCKED`.
   - In embedded Neo4j (mono-writer/embedded transaction model), `selectPending` executes a Cypher query:
     `MATCH (e:OutboxEvent {organizationId: $organizationId, status: 'pending'}) RETURN e ORDER BY e.createdAt ASC LIMIT $limit`.
   - `markDispatched` and `markFailed` update the node in Cypher.

---

### Schema Initializer & Configuration Changes

1. **`Neo4jSchemaInitializer.kt`**
   - Add uniqueness constraints:
     - `agent_step_attempt_id_unique`: `CREATE CONSTRAINT agent_step_attempt_id_unique IF NOT EXISTS FOR (n:AgentStepAttempt) REQUIRE n.id IS UNIQUE`
     - `agent_step_result_id_unique`: `CREATE CONSTRAINT agent_step_result_id_unique IF NOT EXISTS FOR (n:AgentStepResult) REQUIRE n.id IS UNIQUE`
     - `result_capability_id_unique`: `CREATE CONSTRAINT result_capability_id_unique IF NOT EXISTS FOR (n:ResultCapability) REQUIRE n.id IS UNIQUE`
     - `outbox_event_id_unique`: `CREATE CONSTRAINT outbox_event_id_unique IF NOT EXISTS FOR (n:OutboxEvent) REQUIRE n.id IS UNIQUE`
     - `idempotency_record_id_unique`: `CREATE CONSTRAINT idempotency_record_id_unique IF NOT EXISTS FOR (n:IdempotencyRecord) REQUIRE n.id IS UNIQUE`
   - Add indexes for lookup performance:
     - `result_capability_token_hash`: `CREATE INDEX result_capability_token_hash IF NOT EXISTS FOR (n:ResultCapability) ON (n.organizationId, n.workstreamId, n.tokenHash)`
     - `outbox_event_pending`: `CREATE INDEX outbox_event_pending IF NOT EXISTS FOR (n:OutboxEvent) ON (n.organizationId, n.status, n.createdAt)`

2. **`Neo4jPersistenceConfiguration.kt`**
   - Ensure `"io.whozoss.factory.agentattempt.persistence"` is included in `@EnableNeo4jRepositories(basePackages = [...])`.

---

## File Operations & Modifications

### 1. Deletions
- Delete `factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/JdbcOracleExecutionRepository.kt` (if present; verify and ensure cleanup).
- Delete `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcAgentStepAttemptRepository.kt`.
- Delete `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcAgentStepResultRepository.kt`.
- Delete `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/JdbcIdempotencyRepository.kt`.

### 2. New Files (`factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/`)
- `AgentStepAttemptNode.kt`
- `SpringDataNeo4jAgentStepAttemptRepository.kt`
- `Neo4jAgentStepAttemptRepository.kt`
- `AgentStepResultNode.kt`
- `ResultCapabilityNode.kt`
- `SpringDataNeo4jAgentStepResultRepository.kt`
- `SpringDataNeo4jResultCapabilityRepository.kt`
- `Neo4jAgentStepResultRepository.kt`
- `OutboxEventNode.kt`
- `SpringDataNeo4jOutboxRepository.kt`
- `IdempotencyRecordNode.kt`
- `SpringDataNeo4jIdempotencyRepository.kt`
- `Neo4jIdempotencyRepository.kt`

### 3. Modifications
- `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/OutboxDrainService.kt`:
  - Replace `NamedParameterJdbcTemplate` with `SpringDataNeo4jOutboxRepository`.
  - Update `pendingOrganizations()`, `selectPending()`, `markDispatched()`, `markFailed()`.
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`:
  - Add Cypher constraints & indexes for `AgentStepAttempt`, `AgentStepResult`, `ResultCapability`, `OutboxEvent`, `IdempotencyRecord`.
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`:
  - Add `"io.whozoss.factory.agentattempt.persistence"` to `basePackages`.

### 4. Tests Update
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultServiceIntegrationTest.kt`:
  - Change base class from `DomainIntegrationTest` to `Neo4jDomainIntegrationTest` (or `Neo4jIntegrationTest`).
  - Replace direct `jdbcTemplate` query assertions/verifications with SDN repository lookups or Cypher queries via `neo4jDriver` / `Neo4jTestSupport`.
  - Remove trigger-based SQL error simulation tests or adapt outbox failure test using a mock/failing component if applicable.
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/OutboxDrainServiceIntegrationTest.kt`:
  - Change base class to `Neo4jDomainIntegrationTest` / `Neo4jIntegrationTest`.
  - Replace `jdbcTemplate` inserts/queries with `SpringDataNeo4jOutboxRepository` or `neo4jDriver` operations.
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultControllerHttpTest.kt`:
  - Ensure compatibility with Neo4j-backed integration setup.

---

## Step-by-Step Implementation Outline

1. **Clean up Legacy JDBC Oracle Repository**
   - Remove `JdbcOracleExecutionRepository.kt` if present.

2. **Implement Node Projections & Spring Data Neo4j Repositories**
   - Create Node classes and Spring Data Neo4j interfaces for Attempt, Result, Capability, Outbox, and Idempotency.

3. **Implement Domain Repository Adapters**
   - Implement `Neo4jAgentStepAttemptRepository`, `Neo4jAgentStepResultRepository`, `Neo4jIdempotencyRepository`.

4. **Adapt OutboxDrainService**
   - Switch `OutboxDrainService` from `NamedParameterJdbcTemplate` to `SpringDataNeo4jOutboxRepository`.

5. **Update Neo4j Configuration & Schema Initializer**
   - Update `Neo4jPersistenceConfiguration` package list.
   - Update `Neo4jSchemaInitializer` with constraints and indexes.

6. **Remove JDBC Implementations**
   - Delete `JdbcAgentStepAttemptRepository.kt`, `JdbcAgentStepResultRepository.kt`, `JdbcIdempotencyRepository.kt`.

7. **Migrate & Fix Integration Tests**
   - Update `AgentStepResultServiceIntegrationTest.kt`, `OutboxDrainServiceIntegrationTest.kt`, `AgentStepResultControllerHttpTest.kt` to extend `Neo4jDomainIntegrationTest` / `Neo4jIntegrationTest` and verify graph assertions.

8. **Verification**
   - Run `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2` and `./gradlew test --rerun-tasks` in `factory-service` to ensure all unit and integration tests pass cleanly.

---

## Verification & Acceptance Criteria
- [x] Oracle duplicate cleanup confirmed (`JdbcOracleExecutionRepository.kt` deleted/verified absent).
- [x] `AgentStepAttempt`, `AgentStepResult`, `Outbox`, `Idempotency` operating entirely on embedded Neo4j.
- [x] Corresponding JDBC repositories removed.
- [x] All Gradle/Nx tests pass (`./gradlew test`).
