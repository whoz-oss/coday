# Implementation Plan: Finalize Neo4j Embedded Swap in `factory-service`

Swap the remaining legacy PostgreSQL/JDBC infrastructure and test fixtures in `factory-service` to 100% Neo4j embedded. Migrate the `workstream` aggregate to Neo4j SDN, rehouse remaining legacy integration tests onto the in-process `Neo4jDomainIntegrationTest` / `Neo4jIntegrationTest` fixture, drop retired legacy JDBC adapters and Postgres test fixtures, and clean up dead properties/dependencies.

## User Review Required

> [!IMPORTANT]
> - `WorkstreamService` previously returned `Map<String, Any?>` representing workstream shapes. The new `Neo4jWorkstreamRepository` and SDN `WorkstreamNode` maintain full compatibility with this contract and `TenantScope` scoping.
> - `FactoryServiceApplicationIntegrationTest.kt` previously asserted Flyway migrations and PostgreSQL system tables (`information_schema.tables`). Since Flyway and PostgreSQL were removed in Phase 1 and Neo4j SDN now manages graph schema initialization via `Neo4jSchemaInitializer`, `FactoryServiceApplicationIntegrationTest.kt` will be updated to assert Spring Context startup, Actuator health, and Neo4j connectivity / initial graph constraint setup instead of Flyway/PostgreSQL tables.
> - No REST API contracts or artifact blob handling (S3/MinIO/in-memory) will be modified.
> - Neither the Cockpit frontend nor `agentos-factory-bridge-plugin` will be touched.

## Proposed Changes

### 1. Workstream Aggregate Migration to Embedded Neo4j

#### [NEW] `factory-service/src/main/kotlin/io/whozoss/factory/workstream/persistence/WorkstreamNode.kt`
- Create Spring Data Neo4j `@Node("Workstream")` entity.
- Composite ID: `organizationId:workstreamId` (or `@Id val id: String`).
- Properties:
  - `@Indexed organizationId: String`
  - `@Indexed workstreamId: String`
  - `name: String`
  - `status: String`
  - `revision: Int`
  - `createdAt: Instant`
  - `updatedAt: Instant`

#### [NEW] `factory-service/src/main/kotlin/io/whozoss/factory/workstream/persistence/SpringDataNeo4jWorkstreamRepository.kt`
- Spring Data Neo4j interface extending `Neo4jRepository<WorkstreamNode, String>`.
- Custom Cypher methods if needed, or derived finder queries:
  - `findByOrganizationIdOrderByWorkstreamIdAsc(organizationId: String): List<WorkstreamNode>`
  - `findByOrganizationIdAndWorkstreamId(organizationId: String, workstreamId: String): WorkstreamNode?`

#### [NEW] `factory-service/src/main/kotlin/io/whozoss/factory/workstream/persistence/Neo4jWorkstreamRepository.kt`
- Implement `ScopedRepository<Map<String, Any?>, String>` marked with `@Repository` and `@Primary`.
- Replace `JdbcWorkstreamRepository` usages in `WorkstreamService`.
- Expose methods: `findById(scope, id)`, `list(scope)`, `create(scope, workstreamId, name, status)`, `deleteById(scope, id)`.
- Maintain exact key output formatting (`slug`, `name`, `status`, `revision`) matching expected `Map<String, Any?>`.

#### [Neo4jSchemaInitializer.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt)
- Add Cypher schema constraints/indexes for `:Workstream`:
  - `CREATE CONSTRAINT workstream_id_unique IF NOT EXISTS FOR (w:Workstream) REQUIRE w.id IS UNIQUE`
  - `CREATE INDEX workstream_scope IF NOT EXISTS FOR (w:Workstream) ON (w.organizationId, w.workstreamId)`

#### [WorkstreamService.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/workstream/WorkstreamService.kt)
- Inject `Neo4jWorkstreamRepository` (or interface type if preferred) instead of `JdbcWorkstreamRepository`.

---

### 2. Migration & Rehousing of Remaining Integration Tests

#### [WorkstreamJdbcRepositoryTest.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/workstream/WorkstreamJdbcRepositoryTest.kt) -> `WorkstreamRepositoryIntegrationTest.kt`
- Rename/Refactor test class to extend `Neo4jDomainIntegrationTest`.
- Inject `Neo4jWorkstreamRepository` and `WorkstreamService`.
- Execute tests against the in-process Neo4j embedded test engine.

#### [CockpitStaticServingIntegrationTest.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/web/CockpitStaticServingIntegrationTest.kt)
- Switch superclass from `DomainIntegrationTest` to `Neo4jDomainIntegrationTest`.

#### [AgentOsProxyMockTest.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/proxy/AgentOsProxyMockTest.kt)
- Switch superclass from `DomainIntegrationTest` to `Neo4jDomainIntegrationTest`.

#### [CapabilityExecutionIntegrationTest.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityExecutionIntegrationTest.kt)
- Switch superclass from `DomainIntegrationTest` to `Neo4jDomainIntegrationTest`.

#### [FactoryPluginSystemIntegrationTest.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/plugin/FactoryPluginSystemIntegrationTest.kt)
- Switch superclass from `DomainIntegrationTest` to `Neo4jDomainIntegrationTest`. Remove references to retired `PostgresContainerSpec` in KDoc.

#### [FactoryServiceApplicationIntegrationTest.kt](file:///work/app/factory-service/src/test/kotlin/io/whozoss/factory/FactoryServiceApplicationIntegrationTest.kt)
- Switch superclass from `PostgresContainerSpec` to `Neo4jIntegrationTest`.
- Replace Flyway history and `information_schema.tables` assertions with Neo4j driver / Spring context health assertions:
  - Check driver connection and basic graph query execution (`MATCH (n) RETURN count(n)`).
  - Verify Actuator `/actuator/health` endpoint returns `UP`.

---

### 3. Cleanup of Legacy Code & Dead Persistence Classes

#### Remove Retired Files:
- [DELETE] `factory-service/src/main/kotlin/io/whozoss/factory/workflow/persistence/JdbcWorkflowRepository.kt`
- [DELETE] `factory-service/src/main/kotlin/io/whozoss/factory/workstream/JdbcWorkstreamRepository.kt`
- [DELETE] `factory-service/src/test/kotlin/io/whozoss/factory/PostgresContainerSpec.kt`
- [DELETE] `factory-service/src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt`

#### [Neo4jPersistenceConfiguration.kt](file:///work/app/factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt)
- Clean up any outdated references/comments referring to H2 or interim relational datasources.

#### [application.yml](file:///work/app/factory-service/src/main/resources/application.yml) & [application-test.yml](file:///work/app/factory-service/src/test/resources/application-test.yml)
- Remove interim H2 `spring.datasource` configurations and comments regarding PostgreSQL/Flyway/H2 fallback once no JDBC components remain.

#### [build.gradle.kts](file:///work/app/factory-service/build.gradle.kts)
- Remove `spring-boot-starter-data-jdbc` and `libs.h2` runtime dependencies from `factory-service/build.gradle.kts` if no other subproject needs them.
- Check `gradle/libs.versions.toml` if cleanup of unused dependencies is appropriate.

---

## Verification Plan

### Automated Tests
1. Run full test suite for `factory-service`:
   `cd /work/app/factory-service && ./gradlew test`
   Ensure 100% of tests pass without skipped legacy relational conditions.

2. Verify affected Nx tasks from workspace root:
   `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
   `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
   `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`

3. Verify no occurrences of legacy test/persistence classes remain:
   `grep -rn "PostgresContainerSpec\|DomainIntegrationTest\|JdbcWorkflowRepository\|JdbcWorkstreamRepository" factory-service/`
