# Agent-step results and transactional outbox

## What changed

Aggregate A7 is now implemented in `factory-service` under `io.whozoss.factory.agentattempt`, porting capability-bound agent-step result submission from Node/TypeScript to Kotlin/Spring Boot. The implementation uses the existing V4/V6 tables; no Flyway migration was added or changed.

The domain layer models PASS/FAIL results, claims, artifacts, findings, capabilities, attempts, and submission outcomes. `AgentStepResultValidation` enforces the structured payload contract: strict allowed fields, required values, size/count limits, UTF-8 artifact limits, and the permitted finding severities. `CanonicalJsonHash` recursively sorts object keys, preserves array order and nulls, and produces the Node-compatible `sha256:<hex>` digest; token comparisons use constant-time equality.

Persistence is implemented with `NamedParameterJdbcTemplate` and tenant-scoped queries. Capability issuance stores only a SHA-256 token digest and binds the capability to an existing attempt. Because the existing schema makes `result_capabilities` reference `agent_step_results`, issuance creates a `capability-reserved` result row; submission updates that reservation to the submitted payload. A successful submit atomically writes/updates the result, terminalizes the attempt (`completed` for PASS or `failed` for FAIL), and inserts a pending `result_submitted` outbox event in one transactional boundary. Unknown, expired, mismatched, duplicate, and semantically divergent submissions map to the specified machine error codes. Idempotent identical submissions return the existing result without adding another result or outbox event.

`AgentStepResultService` adds the optional `idempotency_records` layer. The canonical request hash detects divergent reuse of a key (`IDEMPOTENCY_KEY_COLLISION`), while exact replays return the cached response. `OutboxDrainService` polls pending events with tenant scoping, creation-time ordering, a limit, and `FOR UPDATE SKIP LOCKED`; successful handlers mark events `dispatched`, and thrown handler errors mark them `failed` while incrementing attempts.

## HTTP contract

`AgentStepResultController` exposes `POST /api/factory/agent-step-results`. It accepts the Node-compatible `{attemptId, result}` shape as well as `{business, observed}`, with optional AgentOS identity headers and `X-Idempotency-Key`. It requires a verified tenant trust context and `Authorization: Bearer <capability>`. Success responses are `{data: {resultId, idempotent, resultHash}}`, using 201 for a new result and 200 for an idempotent replay. Shared exception handling supplies the standard `{error: {code, message, details}}` envelope and the required 400/401/409/410 mappings. The factory OpenAPI document now describes the endpoint, request shapes, envelopes, headers, and responses.

## Files carrying the change

- Domain and hashing: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/AgentStepResultModels.kt`, `AgentStepResultValidation.kt`, `CanonicalJsonHash.kt`
- JDBC ports/adapters: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/persistence/AgentStepAttemptRepository.kt`, `AgentStepResultRepository.kt`, `IdempotencyRepository.kt`, `JdbcAgentStepAttemptRepository.kt`, `JdbcAgentStepResultRepository.kt`, `JdbcIdempotencyRepository.kt`
- Application services: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/service/AgentStepResultService.kt`, `OutboxDrainService.kt`
- HTTP boundary: `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/web/AgentStepResultController.kt`, `AgentStepResultDtos.kt`
- API description: `factory-service/openapi/factory-openapi.yaml`
- Test base and coverage: `factory-service/src/test/kotlin/io/whozoss/factory/DomainIntegrationTest.kt`, `agentattempt/AgentStepResultControllerHttpTest.kt`, `AgentStepResultServiceIntegrationTest.kt`, `agentattempt/OutboxDrainServiceIntegrationTest.kt`, `agentattempt/domain/AgentStepResultValidationTest.kt`, and `agentattempt/domain/CanonicalJsonHashTest.kt`
- Design/verification notes: `specs/12a3ae7b_agent_step_results_outbox_port.md`

All new database-backed integration test classes extend `DomainIntegrationTest`, covering the HTTP contract, atomic rollback, semantic collision, capability expiry and issuance conflicts, idempotency collisions/replays, and outbox polling/state transitions. The intended verification command is `cd factory-service && ./gradlew clean test`; the unit tests separately cover validation limits and canonical hashing.
