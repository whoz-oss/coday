# Oracle aggregate Kotlin port

## What changed

The factory-service now contains the ORACLES aggregate under `io.whozoss.factory.oracle`. It ports the oracle-definition contract and execution lifecycle to Kotlin/Spring Boot while keeping tenant scoping and the existing factory error envelope.

- `OracleDefinition` models schema version `1`, identity, executable `argv`, repository-root working directory, timeout, success rule, and applicability. `OracleDefinitionValidator` strictly checks fields, identifiers, versions, argument limits/content, timeouts, applicability, and rejects shell interpreters and shell-evaluation flags. It also provides canonical JSON hashing and filename identity parsing.
- `OracleDefinitionRegistry` loads `.json` definitions at startup from `factory.oracle.definitions-root`, then `FACTORY_ORACLE_DEFINITIONS_ROOT`, defaulting to `factory/oracles`. It validates each file, enforces `<id>@<version>.json`, rejects duplicate IDs, and exposes `list()`/`get()`. A missing directory produces an empty registry.
- `OracleExecution` represents a tenant-scoped `oracle_executions` row with composite execution identity, status (`RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`), revision, evidence/artifact links, payload, and timestamps.
- `JdbcOracleExecutionRepository` uses parameterized SQL and requires a `TenantScope` for every operation. Status updates are compare-and-swap updates on `revision`; stale revisions raise `REVISION_CONFLICT`, and missing executions raise `NOT_FOUND`. Idempotency lookup uses `payload->>'idempotencyKey'`.
- `OracleExecutionService` records a run as `RUNNING`, terminalizes it as `SUCCEEDED`, supports idempotent replay, and coordinates optional evidence/artifact extension points. Artifact publication is invoked in the same transaction when an artifact ID is supplied; evidence publication is similarly abstracted and optional. The service deliberately records the run rather than spawning the oracle process.
- `OracleController` adds `POST /api/factory/workflows/{workflowId}/steps/{stepId}/oracles/{oracleId}/runs`. It obtains the tenant scope from the verified `TrustContext`, accepts `namespaceId` plus an optional body or `Idempotency-Key`, returns `{ "data": ... }`, uses `201` for a new run and `200` for replay, and relies on `FactoryExceptionHandler` for machine-coded error envelopes. The OpenAPI document describes this route and its request/response schemas.

## Files carrying the change

Implementation:

- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleDefinition.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleDefinitionValidator.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleExceptions.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleExecution.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/registry/OracleDefinitionRegistry.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/OracleExecutionRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/JdbcOracleExecutionRepository.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/service/OracleExecutionService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/publisher/OracleArtifactPublisher.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/publisher/OracleEvidencePublisher.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/web/OracleController.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/web/OracleDtos.kt`
- `factory-service/openapi/factory-openapi.yaml`

Tests and verification coverage:

- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleDefinitionValidatorTest.kt` covers accepted definitions, strict-field validation, shell restrictions, timeout/applicability rules, identity parsing, and stable hashes.
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleDefinitionRegistryTest.kt` covers loading, missing roots, non-JSON files, filename mismatches, invalid files, and duplicate IDs.
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleExecutionRepositoryTest.kt` covers PostgreSQL persistence, lifecycle/revision increments, stale-revision conflicts, missing executions, tenant isolation, idempotent service replay, and artifact publisher wiring. It extends `PostgresContainerSpec` and uses Testcontainers when Docker is available.
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleControllerIntegrationTest.kt` covers authenticated HTTP creation, the data envelope, unknown-oracle and malformed-request codes, and idempotent replay against PostgreSQL.
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleControllerAuthenticationTest.kt` verifies an unauthenticated request is rejected with `401`/`UNAUTHENTICATED`.
- `specs/9c104d1e_oracles_aggregate_kotlin_port.md` records the port specification.

## Using and verifying it

Provide definition files such as `smoke@1.0.0.json` in the configured definitions root. A valid definition must contain the required schema, executable, timeout, success, and applicability fields; its filename must match the definition identity.

Call the run endpoint with an authenticated trust context, for example:

```http
POST /api/factory/workflows/<workflowId>/steps/<stepId>/oracles/<oracleId>/runs
Idempotency-Key: <optional-key>
Content-Type: application/json

{"namespaceId":"<namespaceId>"}
```

A new recorded run returns `201` and a `data` payload; repeating the same idempotency key in the same tenant scope returns `200` with the existing execution. Unknown definitions and malformed requests use the aggregate machine codes (`ORACLE_NOT_FOUND`, `INVALID_ORACLE_RUN_REQUEST`), while stale repository revisions use `REVISION_CONFLICT`.

Run the factory-service Gradle test suite to execute the unit and PostgreSQL/Testcontainers coverage. Tests requiring Docker are configured to disable themselves when no Docker daemon is available.
