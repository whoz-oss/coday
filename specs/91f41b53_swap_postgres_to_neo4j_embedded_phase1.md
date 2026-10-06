# Implementation Plan - SWAP POSTGRES -> NEO4J EMBEDDED - PHASE 1

This plan details Phase 1 of replacing Postgres/Flyway in `factory-service` with an embedded Neo4j engine. Phase 1 focuses on building the Neo4j foundation (dependencies, configuration, schema initializer, test harness) and migrating the Oracle and Artifact persistence repositories while removingFlyway and Postgres dependencies.

## Key Intentions & Architecture

1. **Gradle Build Alignment**:
   - Align `factory-service/build.gradle.kts` and `factory-service/gradle/libs.versions.toml` with `agentos/agentos-service`.
   - Remove `flyway-core`, `flyway-database-postgresql`, `postgresql`, `testcontainers-postgresql`, `spring-boot-starter-data-jdbc`, `h2`.
   - Add `spring-boot-starter-data-neo4j`, `neo4j-embedded` (`org.neo4j:neo4j:2026.02.2`), `neo4j-harness` (`org.neo4j.test:neo4j-harness:2026.02.2`), and Netty 4.2.9.Final runtime overrides / global SLF4J provider exclusions.

2. **Schema & Config**:
   - Remove `factory-service/src/main/resources/db/migration/` folder.
   - Update `application.yml` to set `factory.persistence.mode=embedded-neo4j`, default embedded Bolt URI (`bolt://localhost:7688`), data directory (`build/neo4j-data`), and remove datasource/flyway configurations.
   - Add `PersistenceConfigProperties.kt` (or update existing config properties) to hold `embeddedBoltHost`, `embeddedBoltPort`, `dataDir`, etc.
   - Create `EmbeddedNeo4jConfiguration.kt` and `Neo4jPersistenceConfiguration.kt` under `io.whozoss.factory.config` (matching AgentOS patterns).
   - Create `Neo4jSchemaInitializer.kt` (`ApplicationRunner`) defining constraints & indexes (`CREATE CONSTRAINT IF NOT EXISTS ...`).

3. **Oracle Execution Migration**:
   - Create `OracleExecutionNode` (Spring Data Neo4j `@Node("OracleExecution")`) mapped with composite key or unique identity, properties, labels.
   - Create Spring Data `Neo4jOracleExecutionRepository` interface extending `Neo4jRepository<OracleExecutionNode, String>`.
   - Implement `Neo4jOracleExecutionRepository` implementing `OracleExecutionRepository` port:
     - Handle tenant scoping (`organizationId`, `workstreamId`).
     - Handle optimistic concurrency control (revision matching, throwing `RevisionConflictException` on mismatch).
     - Store and retrieve `payload`, `evidenceId`, `artifactId`, `status`, etc.

4. **Artifact Persistence Migration**:
   - Create `ArtifactMetadataNode` `@Node("ArtifactMetadata")` with fields for `artifactId`, `organizationId`, `workstreamId`, `owner`, `contentType`, `hash`, `size`, `storageKey`, `availabilityStatus`, `retentionDays`, `expiresAt`, `legalHold`, `legalHoldReason`, `legalHoldSetAt`, `purgedAt`, `purgeReason`, `createdAt`, `updatedAt`.
   - Create Spring Data `SpringDataNeo4jArtifactRepository` extending `Neo4jRepository<ArtifactMetadataNode, String>`.
   - Implement `Neo4jArtifactStore` replacing `PostgresArtifactStore`:
     - Retain `ArtifactBlobClient` / blob storage intact (`ArtifactBlobClient` handles MinIO/S3/in-memory).
     - Implement `ArtifactStore` and `ArtifactGcMetadataLister` using Neo4j node operations.
   - Update `ArtifactConfiguration.kt` to wire `Neo4jArtifactStore`.

5. **Testing Harness & Context Harmonization**:
   - Create `EmbeddedNeo4jTestConfiguration.kt` in `io.whozoss.factory.persistence.neo4j` using `Neo4jBuilders.newInProcessBuilder()`.
   - Update `PostgresContainerSpec` (rename/refactor to `Neo4jContainerSpec` or `Neo4jTestSpec` / update `DomainIntegrationTest`) to use `@ActiveProfiles("test", "embedded-neo4j")` and remove PostgreSQL container.
   - Update `OracleExecutionRepositoryTest`, `PostgresArtifactStoreIntegrationTest` (rename to `Neo4jArtifactStoreIntegrationTest`), `OracleControllerIntegrationTest`, and `OracleControllerAuthenticationTest` to run against embedded Neo4j.

---

## Detailed Step-by-Step Action Plan

### Step 1: Dependencies and Build File Cleanup
- **`factory-service/gradle/libs.versions.toml`**:
  - Add version `neo4jEmbedded = "2026.02.2"`, `netty = "4.2.9.Final"`.
  - Add libraries: `spring-boot-starter-data-neo4j`, `neo4j-embedded` (`module = "org.neo4j:neo4j"`), `neo4j-harness` (`module = "org.neo4j.test:neo4j-harness"`).
  - Remove flyway, postgresql, testcontainers-postgresql, h2.
- **`factory-service/build.gradle.kts`**:
  - Remove `spring-boot-starter-data-jdbc`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `h2`, `testcontainers-postgresql`.
  - Add `spring-boot-starter-data-neo4j`, `libs.neo4j.embedded` (with `org.slf4j` and `org.neo4j.driver` exclusions), `libs.neo4j.harness` (testImplementation with exclusions), and direct runtime Netty 4.2.9.Final overrides (`io.netty:netty-transport-classes-epoll`, `kqueue`, `common`, `buffer`, `transport`, `handler`, `codec`, `resolver`).
  - Add global resolutionStrategy and `configurations.all { exclude(group = "org.neo4j", module = "neo4j-slf4j-provider") }`.

### Step 2: Delete Obsolete Migrations
- Remove `factory-service/src/main/resources/db/migration/*` (SQL files V1..V9).

### Step 3: Application Configuration & Neo4j Infrastructure Beans
- **`factory-service/src/main/kotlin/io/whozoss/factory/config/PersistenceConfigProperties.kt`**:
  - Define `@ConfigurationProperties(prefix = "factory.persistence")` class with `mode`, `dataDir`, `embeddedBoltHost`, `embeddedBoltPort`, `embeddedTransactionTimeoutSeconds`.
- **`factory-service/src/main/kotlin/io/whozoss/factory/config/EmbeddedNeo4jConfiguration.kt`**:
  - Set up in-process `DatabaseManagementService` and `Driver` bean when `factory.persistence.mode=embedded-neo4j` and profile `!test`.
- **`factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`**:
  - Enable `@EnableNeo4jRepositories(basePackages = ["io.whozoss.factory"])` and transaction management.
- **`factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`**:
  - Create `ApplicationRunner` bean executing Cypher statements to create constraints and indexes:
    - Constraint on `OracleExecution` (`composite_key` or `(organizationId, workstreamId, namespaceId, workflowId, executionId)`).
    - Constraint on `ArtifactMetadata` (`artifactId`).
    - Indexes on lookup fields like `idempotencyKey` and `organizationId`/`workstreamId`.
- **`factory-service/src/main/resources/application.yml`**:
  - Remove `spring.datasource` and `spring.flyway`.
  - Add `factory.persistence` configuration.
  - Set `spring.neo4j.uri: bolt://${FACTORY_EMBEDDED_BOLT_HOST:localhost}:${FACTORY_EMBEDDED_BOLT_PORT:7688}`.
- **`factory-service/src/main/resources/application-openapi.yml`**:
  - Update to remove datasource/H2 references and configure openapi profile with Neo4j.

### Step 4: Oracle Repository Migration
- **`factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/OracleExecutionNode.kt`**:
  - Neo4j entity representing `OracleExecution`. Key fields: `id` (or composite string ID), `organizationId`, `workstreamId`, `namespaceId`, `workflowId`, `executionId`, `oracleId`, `status`, `revision`, `evidenceId`, `artifactId`, `payload`, `idempotencyKey`, `createdAt`, `updatedAt`.
- **`factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/SpringDataNeo4jOracleRepository.kt`**:
  - Neo4jRepository for `OracleExecutionNode`. Cypher query helper for finding by scope and composite key or idempotency key.
- **`factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/Neo4jOracleExecutionRepository.kt`**:
  - Replaces `JdbcOracleExecutionRepository.kt`.
  - Implements `OracleExecutionRepository`.
  - Implements optimistic locking: checks `expectedRevision` against node revision, throws `RevisionConflictException` on mismatch, increments revision on update.

### Step 5: Artifact Repository Migration
- **`factory-service/src/main/kotlin/io/whozoss/factory/artifact/infrastructure/persistence/ArtifactMetadataNode.kt`**:
  - Neo4j entity `@Node("ArtifactMetadata")` representing artifact metadata.
- **`factory-service/src/main/kotlin/io/whozoss/factory/artifact/infrastructure/persistence/SpringDataNeo4jArtifactRepository.kt`**:
  - Neo4jRepository for `ArtifactMetadataNode`.
- **`factory-service/src/main/kotlin/io/whozoss/factory/artifact/infrastructure/persistence/Neo4jArtifactStore.kt`**:
  - Replaces `PostgresArtifactStore.kt`.
  - Implements `ArtifactStore` and `ArtifactGcMetadataLister`.
  - Keeps binary object storage untouched via `ArtifactBlobClient`.
- **`factory-service/src/main/kotlin/io/whozoss/factory/artifact/config/ArtifactConfiguration.kt`**:
  - Update Spring bean definitions to construct `Neo4jArtifactStore` instead of `PostgresArtifactStore`.

### Step 6: Test Harness and Test Integration Updates
- **`factory-service/src/test/kotlin/io/whozoss/factory/persistence/neo4j/EmbeddedNeo4jTestConfiguration.kt`**:
  - Test configuration providing `@Primary Driver` and `Neo4j` harness beans.
- **Refactor `PostgresContainerSpec` to `Neo4jContainerSpec` / update integration test base**:
  - Replace Postgres container logic with `@ActiveProfiles("test", "embedded-neo4j")` and `@Import(EmbeddedNeo4jTestConfiguration::class)`.
  - Clean up database between test executions using Cypher (`MATCH (n) DETACH DELETE n`).
- **Update specific test classes**:
  - `OracleExecutionRepositoryTest`: test `Neo4jOracleExecutionRepository` operations, revisions, fencing, idempotency key lookups.
  - Rename `PostgresArtifactStoreIntegrationTest` to `Neo4jArtifactStoreIntegrationTest`: verify put, get, delete, legal hold, purge, and GC listing against Neo4j.
  - `OracleControllerIntegrationTest` & `OracleControllerAuthenticationTest`: verify REST endpoints work end-to-end.

---

## Verification Plan

### Build & Tests
1. Run Nx test for factory-service:
   `pnpm nx test factory-service`
2. Verify affected tests pass:
   `pnpm nx affected -t test --base="$(cat /work/data/baseline)"`
3. Verify lint and build:
   `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
   `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`

### Inspection
- Confirm no references to `flyway` or `postgresql` remain in `factory-service`.
- Confirm `OracleExecution` and `Artifact` integration tests execute against `EmbeddedNeo4jTestConfiguration`.
