# Plan: Swap Postgres to Neo4j Embedded - Phase 2 (Delivery, Worker, Lease, WorkUnit, Environment)

## Overview & Goal
Migrate the delivery, worker, lease, workunit, and environment repository implementations in `factory-service` from JDBC/PostgreSQL to embedded Neo4j (`Neo4jRepository` via Spring Data Neo4j).

Specifically:
1. **Delivery Aggregate**:
   - Create `DeliveryRecordNode` (or `DeliveryNode` + related node/relationship projections as needed by delivery domain) representing the delivery snapshot and operations journal in Neo4j.
   - Replace `SqlDeliveryRepository` with `Neo4jDeliveryRepository`.
   - Implement record sequence generation via Cypher query `MAX(r.recordSequence) + 1` or property counter / COALESCE.
2. **Worker, Lease, WorkUnit, Environment Aggregates**:
   - Create `LeaseNode`, `WorkerNode`, `WorkUnitNode`, and `EnvironmentNode` domain node entities.
   - Replace `JdbcLeaseRepository` with `Neo4jLeaseRepository`. Implement fencing token counter generation and CAS/update via property query.
   - Replace `JdbcWorkerRepository`, `JdbcWorkUnitRepository`, `JdbcEnvironmentRepository` with SDN-backed implementations (`Neo4jWorkerRepository`, `Neo4jWorkUnitRepository`, `Neo4jWorkEnvironmentRepository`).
3. **Configuration & Wiring**:
   - Register new Spring Data Neo4j interfaces in `Neo4jPersistenceConfiguration` (`basePackages`).
   - Register domain repository implementations as `@Repository` / `@Primary` Spring beans in `factory-service`.
   - Update `Neo4jSchemaInitializer` to ensure indexes/constraints for Delivery, Worker, Lease, WorkUnit, and WorkEnvironment node types.
4. **Integration Tests & Base Test Class Updates**:
   - Update `DomainIntegrationTest` to inherit from `Neo4jIntegrationTest` (instead of `PostgresContainerSpec`), clearing Neo4j database nodes via `Neo4jTestSupport.clearDatabase(neo4jDriver)` in `@BeforeEach`.
   - Ensure all affected integration tests (`DeliveryRepositoryIntegrationTest`, `LeaseFencingAndExpiryTest`, `WorkUnitAndWorkerIntegrationTest`, `WorkUnitLeaseConcurrencyTest`, `WorkEnvironmentLifecycleServiceTest`, `EnvironmentLifecycleControllerTest`) run green against the embedded Neo4j test harness.

---

## Detailed Implementation Steps

### Phase 1: Spring Data Neo4j Nodes & Repository Interfaces

1. **Delivery Nodes & Repository**:
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/DeliveryNode.kt`
     - Node label: `:Delivery` (composite `@Id val id: String` = `$organizationId|$workstreamId|$namespaceId|$deliveryId`).
     - Node label: `:DeliveryJournalRecord` for operations journal entries, or single node with stored JSON/properties.
     - Store properties: `organizationId`, `workstreamId`, `namespaceId`, `deliveryId`, `revision`, `state`, `snapshotJson`, `createdAt`, `updatedAt`.
     - Sequence generation for journal records: Cypher `MATCH (d:Delivery {id: $id})-[:HAS_RECORD]->(r:DeliveryRecord) RETURN coalesce(max(r.recordSequence), 0) + 1` or storing `nextRecordSequence` on `:Delivery`.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/SpringDataNeo4jDeliveryRepository.kt`
     - SDN interface `Neo4jRepository<DeliveryNode, String>`.
     - `@Query` method for CAS update and sequence calculation.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/delivery/persistence/Neo4jDeliveryRepository.kt`
     - Replaces `SqlDeliveryRepository`. Implements `DeliveryRepository`.
     - Annotated with `@Repository` and `@Primary`.

2. **Lease Node & Repository**:
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/lease/persistence/LeaseNode.kt`
     - Node label: `:WorkUnitLease`.
     - `@Id val id: String` (`$organizationId|$workstreamId|$workUnitId|$leaseId`).
     - Properties: `organizationId`, `workstreamId`, `workUnitId`, `leaseId`, `workerId`, `fencingToken` (Long), `status`, `acquiredAt`, `expiresAt`, `releasedAt`, `expiryReason`.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/lease/persistence/SpringDataNeo4jLeaseRepository.kt`
     - SDN interface `Neo4jRepository<LeaseNode, String>`.
     - Query for monotone fencing sequence generation (e.g., query max fencing token across leases or maintain a global/scoped sequence counter).
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/lease/persistence/Neo4jLeaseRepository.kt`
     - Replaces `JdbcLeaseRepository`. Implements `LeaseRepository`.
     - Handles atomic `acquire` (SELECT FOR UPDATE SKIP LOCKED equivalent in Cypher / atomic property updates), `renew`, `release`, `expire`, `findByLeaseId`, and `findActiveLeaseByWorkUnit`.

3. **Worker Node & Repository**:
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/worker/persistence/WorkerNode.kt`
     - Node label: `:Worker`.
     - `@Id val id: String` (`$organizationId|$workerId` - note worker is org-scoped).
     - Properties: `organizationId`, `workerId`, `state`, `revision`, `lastHeartbeatAt`, `createdAt`, `updatedAt`, `capabilitiesJson`, `metadataJson`.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/worker/persistence/SpringDataNeo4jWorkerRepository.kt`
     - SDN interface with `@Query` for CAS status update and heartbeat update.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/worker/persistence/Neo4jWorkerRepository.kt`
     - Replaces `JdbcWorkerRepository`. Implements `WorkerRepository`.

4. **WorkUnit Node & Repository**:
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/workunit/persistence/WorkUnitNode.kt`
     - Node label: `:WorkUnit`.
     - `@Id val id: String` (`$organizationId|$workstreamId|$workUnitId`).
     - Properties: `organizationId`, `workstreamId`, `workUnitId`, `workflowId`, `caseId`, `state`, `revision`, `priority`, `requiredEnvironmentId`, `payloadJson`, `createdAt`, `updatedAt`.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/workunit/persistence/SpringDataNeo4jWorkUnitRepository.kt`
     - SDN interface with `@Query` for CAS status updates.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/workunit/persistence/Neo4jWorkUnitRepository.kt`
     - Replaces `JdbcWorkUnitRepository`. Implements `WorkUnitRepository`.

5. **WorkEnvironment Node & Repository**:
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/environment/persistence/WorkEnvironmentNode.kt`
     - Node label: `:WorkEnvironment`.
     - `@Id val id: String` (`$organizationId|$workstreamId|$environmentId`).
     - Properties: `organizationId`, `workstreamId`, `environmentId`, `workflowId`, `status`, `revision`, `payloadJson`, `createdAt`, `updatedAt`.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/environment/persistence/SpringDataNeo4jWorkEnvironmentRepository.kt`
     - SDN interface with `@Query` for CAS descriptor updates and `findLatestByWorkflowId`.
   - File: `factory-service/src/main/kotlin/io/whozoss/factory/environment/persistence/Neo4jWorkEnvironmentRepository.kt`
     - Replaces `JdbcWorkEnvironmentRepository`. Implements `WorkEnvironmentRepository`.

---

### Phase 2: Configuration & Schema Initializer Updates

1. **`Neo4jPersistenceConfiguration.kt`**:
   - Update `@EnableNeo4jRepositories` `basePackages` to include:
     - `io.whozoss.factory.delivery.persistence`
     - `io.whozoss.factory.lease.persistence`
     - `io.whozoss.factory.worker.persistence`
     - `io.whozoss.factory.workunit.persistence`
     - `io.whozoss.factory.environment.persistence`

2. **`Neo4jSchemaInitializer.kt`**:
   - Add Cypher constraints / indexes for:
     - `:Delivery(id)` (IS UNIQUE)
     - `:WorkUnitLease(id)` (IS UNIQUE)
     - `:Worker(id)` (IS UNIQUE)
     - `:WorkUnit(id)` (IS UNIQUE)
     - `:WorkEnvironment(id)` (IS UNIQUE)

3. **Cleanup Old JDBC Repositories**:
   - Remove or deprecate `SqlDeliveryRepository`, `JdbcLeaseRepository`, `JdbcWorkerRepository`, `JdbcWorkUnitRepository`, `JdbcWorkEnvironmentRepository` (or unregister them as `@Repository` beans so Neo4j implementations take over).

---

### Phase 3: Integration Tests Migration

1. **`DomainIntegrationTest.kt`**:
   - Change superclass from `PostgresContainerSpec` to `Neo4jIntegrationTest`.
   - Remove `JdbcTemplate` table deletion in `resetControlPlane()`.
   - Call `Neo4jTestSupport.clearDatabase(neo4jDriver)` in `@BeforeEach`.

2. **Run and Verify Integration Tests**:
   - `DeliveryRepositoryIntegrationTest`
   - `LeaseFencingAndExpiryTest`
   - `WorkUnitAndWorkerIntegrationTest`
   - `WorkUnitLeaseConcurrencyTest`
   - `WorkEnvironmentLifecycleServiceTest`
   - `EnvironmentLifecycleControllerTest`

---

## Verification Plan

### Execution Command
Run the test suite using Gradle:
```bash
cd factory-service && ./gradlew test --rerun-tasks
```

### Checks
- All tests in `factory-service` pass without requiring PostgreSQL or Docker containers.
- Monotone sequence ordering for delivery records and fencing tokens for leases are verified under concurrent/sequential tests.
