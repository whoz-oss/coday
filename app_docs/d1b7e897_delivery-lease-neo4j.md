# Phase 2: embedded Neo4j persistence for delivery and execution control

## What changed

`factory-service` now selects Spring Data Neo4j adapters as the primary implementations for the delivery, lease, worker, work-unit, and work-environment repository ports. The domain types and HTTP contracts remain behind the existing repository interfaces; the former JDBC/SQL adapters are no longer the active implementations.

The persistence model is node-based and tenant-scoped:

- `DeliveryNode` stores the current delivery snapshot and `DeliveryRecordNode` stores the append-only journal. `Neo4jDeliveryRepository` preserves the existing idempotency, optimistic revision, operation-transition, rollback, and corruption checks while serialising snapshots as JSON node properties. Journal entries receive monotonically increasing `recordSequence` values from the current maximum plus one, and multi-record mutations run transactionally.
- `LeaseNode` backs `Neo4jLeaseRepository`. Lease acquisition selects reclaimable work, derives the next fencing token from the stored high-water mark, updates the work unit, and persists the lease in a new transaction. A process-local acquisition lock provides the single-process embedded equivalent of the former row-locking claim path. Renew, release, expiry, requeue, and stale-token validation are retained.
- `WorkerNode`, `WorkUnitNode`, and `WorkEnvironmentNode` hold the corresponding aggregate data, including JSON payloads where the previous JDBC model used JSONB. Their adapters use SDN repositories and Cypher compare-and-swap updates for revision/status/heartbeat changes. Scope checks remain explicit, and environment node `status`/`revision` properties are authoritative when reconstructing the domain object.

The graph schema is now initialized at application startup by idempotent constraints and indexes for delivery snapshots/journal records, leases, workers, work units, environments, plus the previously migrated artifact and oracle nodes. PostgreSQL/Flyway dependencies and migration files were removed; H2 remains only as an interim embedded relational store for repositories not yet migrated.

## Where it lives

- `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/DeliveryNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/DeliveryRecordNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/Neo4jDeliveryRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/SpringDataNeo4jDeliveryRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/lease/persistence/LeaseNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/lease/persistence/Neo4jLeaseRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/lease/persistence/SpringDataNeo4jLeaseRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/worker/persistence/WorkerNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/worker/persistence/Neo4jWorkerRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/worker/persistence/SpringDataNeo4jWorkerRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workunit/persistence/WorkUnitNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workunit/persistence/Neo4jWorkUnitRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/workunit/persistence/SpringDataNeo4jWorkUnitRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/environment/persistence/WorkEnvironmentNode.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/environment/persistence/Neo4jWorkEnvironmentRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/environment/persistence/SpringDataNeo4jWorkEnvironmentRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jPersistenceConfiguration.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/Neo4jSchemaInitializer.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/EmbeddedNeo4jConfiguration.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/config/PersistenceConfigProperties.kt`

Build configuration in `factory-service/build.gradle.kts` and `factory-service/gradle/libs.versions.toml` adds Spring Data Neo4j, the embedded Neo4j engine, and the Neo4j test harness. Netty resolution and logging exclusions accommodate the Neo4j 2026.x dependencies. `application.yml` defaults to `factory.persistence.mode=embedded-neo4j`; `application-embedded-neo4j.yml` makes that profile explicit. The embedded engine starts an in-process Community Edition server over Bolt, with configurable data directory, host, port, and transaction timeout. `factory.persistence.mode=neo4j` remains available for a standalone server.

## Tests and verification

Integration tests now use `Neo4jIntegrationTest` and `EmbeddedNeo4jTestConfiguration`, which start an in-process Neo4j harness without Docker and clear all graph nodes before each test. The delivery, lease, worker/work-unit, environment, artifact, and oracle integration test files were updated to use this fixture; the old PostgreSQL container fixture was adapted away from container dependencies. The test profile is configured in `factory-service/src/test/resources/application-test.yml`, with workflow seeding and background outbox draining disabled.

Run the factory-service tests with:

```bash
pnpm nx test factory-service
```

For local application startup, use the default configuration or activate `embedded-neo4j`; the default embedded Bolt port is `7688` (set `FACTORY_EMBEDDED_BOLT_PORT=0` for an OS-assigned port). OpenAPI generation explicitly combines `openapi,embedded-neo4j` and requests a random embedded Bolt port.

The focused integration coverage is in `factory-service/src/test/kotlin/io/whozoss/factory/delivery/DeliveryRepositoryIntegrationTest.kt`, `factory-service/src/test/kotlin/io/whozoss/factory/lease/LeaseFencingAndExpiryTest.kt`, `factory-service/src/test/kotlin/io/whozoss/factory/workunit/WorkUnitAndWorkerIntegrationTest.kt`, `factory-service/src/test/kotlin/io/whozoss/factory/workunit/WorkUnitLeaseConcurrencyTest.kt`, `factory-service/src/test/kotlin/io/whozoss/factory/environment/WorkEnvironmentLifecycleServiceTest.kt`, and `factory-service/src/test/kotlin/io/whozoss/factory/environment/EnvironmentLifecycleControllerTest.kt`. The diff does not include a test-run result, so execute the command above to confirm the current checkout.
