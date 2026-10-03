# Implementation Plan: Oracle Aggregate (A2) Port to Kotlin/Spring Boot

This plan specifies the porting of the ORACLES aggregate from Node/TS (`factory/`) to Kotlin/Spring Boot inside `factory-service/src/` under package `io.whozoss.factory.oracle`.

---

## User Request Summary
Port the ORACLES aggregate into `factory-service/src/main/kotlin/io/whozoss/factory/oracle/` following the strict scope:
1. **Domain (`io.whozoss.factory.oracle.domain`)**: `OracleDefinition` models with strict validation rules, canonical hashing, and `OracleExecution` aggregate model for `oracle_executions` table (V6 schema).
2. **Registry (`OracleDefinitionRegistry`)**: Spring `@Component` loading JSON definitions from `@Value("\${factory.oracle.definitions-root:\${FACTORY_ORACLE_DEFINITIONS_ROOT:factory/oracles}}")` on startup, checking file path/identity invariants, duplicate ID rejection, and exposes `list()` and `get(id)`.
3. **Persistence (`OracleExecutionRepository`)**: Spring Data JDBC or `NamedParameterJdbcTemplate` implementation following `ScopedRepository` pattern, scoped by `TenantScope`, parameterized SQL, optimistic locking using `revision` column (raising `RevisionConflictException` on mismatch), and idempotency support.
4. **Service & Extensions (`OracleExecutionService`, Publisher Interfaces)**: `OracleExecutionService` managing execution runs and terminalization. Extension interfaces (`OracleArtifactPublisher`, `OracleEvidencePublisher`) for transactional decoupling (e.g. flipping artifact `availability_status = 'available'`).
5. **HTTP Controller & OpenAPI (`OracleController`)**: Route `POST /api/factory/workflows/{workflowId}/steps/{stepId}/oracles/{oracleId}/runs` with `{ "data": ... }` response envelope and standard error handling via `FactoryExceptionHandler`, scoped by `TrustContext` / `TenantScope`, with idempotency key support.
6. **Integration Tests (`PostgresContainerSpec` subclass)**: Comprehensive Testcontainers tests in `factory-service/src/test/kotlin/io/whozoss/factory/oracle/` covering registry startup, repository operations, optimistic locking conflict, controller envelope/status codes, error mapping, and idempotency.

---

## Proposed Architecture & Directory Structure

All new files live under:
- `factory-service/src/main/kotlin/io/whozoss/factory/oracle/`
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/`

```
factory-service/src/main/kotlin/io/whozoss/factory/oracle/
├── domain/
│   ├── OracleDefinition.kt         // Data classes: OracleDefinition, OracleSuccessCondition, OracleApplicableCondition
│   ├── OracleDefinitionValidator.kt// Strict validation & canonical SHA-256 hashing logic
│   └── OracleExecution.kt          // Aggregate root model & status enum (RUNNING, SUCCEEDED, FAILED, CANCELLED)
├── registry/
│   └── OracleDefinitionRegistry.kt // @Component loading JSON definitions from filesystem at startup
├── persistence/
│   ├── OracleExecutionRepository.kt// Interface or implementation with ScopedRepository
│   └── SqlOracleExecutionRepository.kt // NamedParameterJdbcTemplate implementation for oracle_executions (V6)
├── publisher/
│   ├── OracleArtifactPublisher.kt  // Interface for publishing/updating artifacts on terminalization
│   ├── OracleEvidencePublisher.kt  // Interface for recording evidence
│   └── DefaultOracleArtifactPublisher.kt // SQL implementation setting availability_status = 'available' on artifacts
├── service/
│   └── OracleExecutionService.kt   // Domain service executing/recording oracle runs & terminalization
└── web/
    ├── OracleController.kt          // REST Controller handling POST .../oracles/{oracleId}/runs
    └── dto/
        ├── OracleRunRequest.kt      // Request body: namespaceId, idempotencyKey
        └── OracleRunResponse.kt     // Response payload shape
```

And test files under:
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleDefinitionValidatorTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleDefinitionRegistryTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleExecutionRepositoryTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleControllerIntegrationTest.kt`

---

## Technical Specifications

### 1. Domain (`io.whozoss.factory.oracle.domain`)

#### `OracleDefinition.kt`
```kotlin
package io.whozoss.factory.oracle.domain

data class OracleSuccessCondition(
    val rule: String = "exit-code",
    val requireWork: Boolean = true,
)

data class OracleApplicableCondition(
    val workflowTypes: List<String>,
    val stepIds: List<String>,
)

data class OracleDefinition(
    val schemaVersion: String = "1",
    val id: String,
    val version: String,
    val domain: String,
    val argv: List<String>,
    val cwd: String = "repo-root",
    val timeoutMs: Long,
    val success: OracleSuccessCondition,
    val applicable: OracleApplicableCondition,
)
```

#### `OracleDefinitionValidator.kt`
Validation rules (aligned with `factory/src/domain/oracle/oracle-definition.ts`):
- `schemaVersion` must be `"1"`.
- `id`, `version`, `domain` must match regex rules (`id`/`domain`: `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`, `version`: `^\d+\.\d+\.\d+$`).
- `argv`: list of 1 to 32 non-empty strings without CR/LF/null characters. Length <= 512.
- Shell check: Executable name (first element of `argv` without path) MUST NOT be in `sh`, `bash`, `zsh`, `dash`, `ksh`, `cmd`, `cmd.exe`, `powershell`, `powershell.exe`, `pwsh`, `pwsh.exe`.
- Shell flag check: Arguments (index > 0) MUST NOT be `-c`, `--command`, `/c`, `-command`, `-encodedcommand` (case-insensitive).
- `cwd` must be `"repo-root"`.
- `timeoutMs`: 1 to 3,600,000 ms.
- `success.rule` must be `"exit-code"`. `requireWork` must be boolean.
- `applicable.workflowTypes` and `applicable.stepIds`: Non-empty lists of valid safe strings.
- Throws `BadRequestException` with error code `"INVALID_ORACLE_DEFINITION"` (400 or 422) if validation fails.
- Canonical Hash computation: SHA-256 over JSON string of definition with keys recursively sorted alphabetically.

#### `OracleExecution.kt`
```kotlin
package io.whozoss.factory.oracle.domain

import java.time.Instant

enum class OracleExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    fun toDbValue(): String = name.lowercase()

    companion object {
        fun fromDbValue(value: String): OracleExecutionStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown status: $value")
    }
}

data class OracleExecution(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val executionId: String,
    val oracleId: String,
    val status: OracleExecutionStatus = OracleExecutionStatus.RUNNING,
    val revision: Int = 1,
    val evidenceId: String? = null,
    val artifactId: String? = null,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
```

---

### 2. Registry (`io.whozoss.factory.oracle.registry`)

#### `OracleDefinitionRegistry.kt`
- Annotated with `@Component`.
- Injects property `@Value("\${factory.oracle.definitions-root:\${FACTORY_ORACLE_DEFINITIONS_ROOT:factory/oracles}}") private val definitionsRootPath: String`.
- `@PostConstruct` method `init()`:
  1. Resolves `definitionsRootPath` (relative to current working directory or absolute).
  2. If folder does not exist or has no `.json` files, logs warning or initializes empty map (or loads from fallback/classpath if test environment).
  3. Iterates over all `*.json` files:
     - Parses JSON using Jackson `ObjectMapper`.
     - Validates definition using `OracleDefinitionValidator.validate(definition)`.
     - Verifies filename agreement: filename without path and `.json` extension MUST equal `"${definition.id}@${definition.version}"`. On mismatch, throws `BadRequestException` / `FactoryException` with code `"ORACLE_PATH_IDENTITY_MISMATCH"`.
     - Checks duplicate ID: if `items.containsKey(definition.id)`, throws `ConflictException` or `BadRequestException` with code `"DUPLICATE_ORACLE_ID"`.
     - Registers valid definition.
- Exposes:
  - `fun list(): List<OracleDefinition>`
  - `fun get(id: String): OracleDefinition?`

---

### 3. Persistence (`io.whozoss.factory.oracle.persistence`)

#### `OracleExecutionRepository.kt` & `SqlOracleExecutionRepository.kt`
- Implements `ScopedRepository<OracleExecution, OracleExecutionKey>` where composite key is `(namespaceId, workflowId, executionId)`.
- Implemented with `NamedParameterJdbcTemplate` or `JdbcTemplate`.
- Table: `oracle_executions` (V6 migration schema).
  - Columns: `organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `execution_id`, `oracle_id`, `status`, `revision`, `evidence_id`, `artifact_id`, `payload` (JSONB), `created_at`, `updated_at`.
- All SQL queries require `organization_id = :orgId AND workstream_id = :workstreamId` from `TenantScope`.
- Methods:
  - `fun save(scope: TenantScope, execution: OracleExecution): OracleExecution`
    - Inserts row with `revision = 1`.
  - `fun updateStatus(scope: TenantScope, namespaceId: String, workflowId: String, executionId: String, newStatus: OracleExecutionStatus, expectedRevision: Int, artifactId: String? = null, payload: String? = null): OracleExecution`
    - SQL: `UPDATE oracle_executions SET status = :status, revision = revision + 1, artifact_id = COALESCE(:artifactId, artifact_id), payload = COALESCE(:payload, payload::jsonb), updated_at = CURRENT_TIMESTAMP WHERE organization_id = :orgId AND workstream_id = :workstreamId AND namespace_id = :namespaceId AND workflow_id = :workflowId AND execution_id = :executionId AND revision = :expectedRevision RETURNING *`
    - If updated row count == 0:
      - Check if row exists without revision match -> throw `RevisionConflictException("Revision conflict for execution $executionId")` (HTTP 409, error code `REVISION_CONFLICT`).
      - If row does not exist -> throw `ResourceNotFoundException("Oracle execution not found")` (HTTP 404, error code `NOT_FOUND`).
  - `fun findById(scope: TenantScope, key: ExecutionKey): OracleExecution?`
  - `fun findByIdempotencyKey(scope: TenantScope, idempotencyKey: String): OracleExecution?` (or payload search / outbox check).

---

### 4. Extensions & Publishers (`io.whozoss.factory.oracle.publisher`)

#### `OracleArtifactPublisher.kt`
```kotlin
package io.whozoss.factory.oracle.publisher

import io.whozoss.factory.persistence.TenantScope

interface OracleArtifactPublisher {
    fun publishArtifact(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        artifactId: String
    )
}
```

#### `DefaultOracleArtifactPublisher.kt`
- Implements `OracleArtifactPublisher`.
- Uses `NamedParameterJdbcTemplate` to execute:
  `UPDATE artifacts SET availability_status = 'available', updated_at = CURRENT_TIMESTAMP WHERE organization_id = :orgId AND workstream_id = :wsId AND namespace_id = :nsId AND workflow_id = :wfId AND artifact_id = :artifactId`

#### `OracleEvidencePublisher.kt`
```kotlin
package io.whozoss.factory.oracle.publisher

import io.whozoss.factory.persistence.TenantScope

interface OracleEvidencePublisher {
    fun recordEvidence(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        oracleId: String,
        outcome: String,
        facts: Map<String, Any?>
    ): String // returns evidenceId
}
```

---

### 5. Service (`io.whozoss.factory.oracle.service`)

#### `OracleExecutionService.kt`
- Inject `OracleDefinitionRegistry`, `OracleExecutionRepository`, `TenantScopeProvider`, `OracleArtifactPublisher` (optional/autowired), `OracleEvidencePublisher` (optional/autowired).
- Methods:
  - `fun runOracle(scope: TenantScope, workflowId: String, stepId: String, oracleId: String, namespaceId: String, idempotencyKey: String?): OracleExecutionResultDto`
    1. Lookup oracle definition via `registry.get(oracleId)` -> if null, throw `ResourceNotFoundException("Oracle $oracleId not found")` with code `"ORACLE_NOT_FOUND"`.
    2. Check workflow existence & applicability (if required/governed or handle step applicability).
    3. Create initial `OracleExecution` in status `RUNNING`.
    4. Save execution to repository.
    5. Execute/simulate oracle run (or mark outcome based on command outcome).
    6. Terminalize: update status to `SUCCEEDED` or `FAILED`.
    7. If `artifactId` present, call `oracleArtifactPublisher.publishArtifact(...)`.
    8. Return result wrapper with `workflowId`, `stepId`, `executionId`, `status`, `outcome`, etc.
  - `fun terminalize(scope: TenantScope, namespaceId: String, workflowId: String, executionId: String, status: OracleExecutionStatus, expectedRevision: Int, artifactId: String?): OracleExecution`
    - Executes `@Transactional` update and artifact publication in a single database transaction.

---

### 6. Controller (`io.whozoss.factory.oracle.web`)

#### `OracleController.kt`
- Route: `POST /api/factory/workflows/{workflowId}/steps/{stepId}/oracles/{oracleId}/runs`
- Header / Context Injection: `TrustContext` injected via `TrustContextArgumentResolver`.
- Scope resolution: `tenantScopeProvider.scopeOf(trustContext)` -> if null, throws `UnauthenticatedException` (401).
- Request Body DTO (`OracleRunRequest`):
  ```kotlin
  data class OracleRunRequest(
      val namespaceId: String,
      val idempotencyKey: String? = null
  )
  ```
- Header fallback: `Idempotency-Key` header if request body `idempotencyKey` is null.
- Response Envelope:
  ```json
  {
    "data": {
      "workflowId": "...",
      "stepId": "...",
      "executionId": "...",
      "oracleId": "...",
      "status": "SUCCEEDED",
      "revision": 2
    }
  }
  ```
- Exceptions mapped automatically by `FactoryExceptionHandler`:
  - `ResourceNotFoundException` -> 404 `{ "error": { "code": "NOT_FOUND", ... } }` or `"ORACLE_NOT_FOUND"`
  - `RevisionConflictException` -> 409 `{ "error": { "code": "REVISION_CONFLICT", ... } }`
  - `BadRequestException` -> 400 `{ "error": { "code": "INVALID_ORACLE_DEFINITION", ... } }`
  - Duplicate / Conflict -> 409 `{ "error": { "code": "DUPLICATE_ORACLE_ID", ... } }`

---

### 7. OpenAPI Spec Update

Verify/update `factory-service/src/main/resources/application-openapi.yml` or springdoc annotations on `OracleController` to ensure generated OpenAPI spec includes the endpoint under tag `oracles`.

---

### 8. Integration Tests (`io.whozoss.factory.oracle.*Test`)

Create the following tests extending `PostgresContainerSpec`:
1. `OracleDefinitionValidatorTest`: Unit tests for definition validation (shell rejection, flags, timeout range, etc.).
2. `OracleDefinitionRegistryTest`: Tests loading test oracle JSON files from a temporary directory, checking filename identity mismatch, duplicate ID rejection, and `list()` / `get()` methods.
3. `OracleExecutionRepositoryTest` (with `@SpringBootTest` & Testcontainers):
   - Inserting a new execution in `RUNNING` status.
   - Updating status with valid revision counter -> increments revision.
   - Optimistic locking collision: updating with outdated `expectedRevision` -> throws `RevisionConflictException`.
   - Artifact availability flip on terminalization.
4. `OracleControllerIntegrationTest` (with `@SpringBootTest` & Testcontainers & `TestRestTemplate` or `MockMvc`):
   - `POST /api/factory/workflows/{workflowId}/steps/{stepId}/oracles/{oracleId}/runs`
   - Validates response JSON envelope `{ "data": ... }`.
   - Validates error codes: 404 for unknown oracle, 409 for revision conflict, 400 for bad request.
   - Validates tenant isolation using `TrustContext` headers.

---

## File Creation & Modification Plan

| File | Action | Purpose |
|------|--------|---------|
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleDefinition.kt` | Create | Data classes for JSON oracle definitions |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleDefinitionValidator.kt` | Create | Validation rules & canonical SHA-256 hash |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/domain/OracleExecution.kt` | Create | Aggregate root model for `oracle_executions` |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/registry/OracleDefinitionRegistry.kt` | Create | Spring component loading JSON files at startup |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/OracleExecutionRepository.kt` | Create | Persistence interface |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/persistence/SqlOracleExecutionRepository.kt` | Create | JDBC repository with TenantScope & optimistic locking |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/publisher/OracleArtifactPublisher.kt` | Create | Artifact publisher interface & SQL implementation |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/publisher/OracleEvidencePublisher.kt` | Create | Evidence publisher extension interface |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/service/OracleExecutionService.kt` | Create | Execution domain service |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/web/OracleController.kt` | Create | REST Controller for oracle run endpoint |
| `factory-service/src/main/kotlin/io/whozoss/factory/oracle/web/dto/OracleDtos.kt` | Create | Request and response DTOs |
| `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleDefinitionValidatorTest.kt` | Create | Unit tests for validation logic |
| `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleDefinitionRegistryTest.kt` | Create | Registry loading and invariant tests |
| `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleExecutionRepositoryTest.kt` | Create | Testcontainers DB tests for repository & optimistic locking |
| `factory-service/src/test/kotlin/io/whozoss/factory/oracle/OracleControllerIntegrationTest.kt` | Create | Testcontainers HTTP controller integration tests |

---

## Verification Plan

### Automated Tests
1. Gradle Test Suite:
   ```bash
   cd factory-service && ./gradlew test
   ```
2. Lint check:
   ```bash
   pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
   ```
3. Check all error codes match specified codes:
   - `NOT_FOUND`
   - `ORACLE_NOT_FOUND`
   - `REVISION_CONFLICT`
   - `INVALID_ORACLE_DEFINITION`
   - `DUPLICATE_ORACLE_ID`
   - `ORACLE_PATH_IDENTITY_MISMATCH`

---

## Constraints Checklist
- [x] ONLY touch files under `factory-service/src/` (package `io.whozoss.factory.oracle` + tests + config).
- [x] DO NOT touch Node `factory/` or `agentos/`.
- [x] Use existing socle (`io.whozoss.factory.{persistence,error,web,config}`).
- [x] SQL parameterized with `TenantScope(organizationId, workstreamId)`.
- [x] Optimistic locking on `revision` throwing `RevisionConflictException` (409).
- [x] HTTP envelope `{ "data": ... }` and standard error envelope on failure.
- [x] Testcontainers tests extending `PostgresContainerSpec`.
