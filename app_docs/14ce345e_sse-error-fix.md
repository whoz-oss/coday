# Agent-step query and SSE error responses

This change fixes two `factory-service` integration issues without changing normal SSE streaming:

- The outbox assertion in `AgentStepResultServiceIntegrationTest` now selects only `event_type`, matching the single-column `jdbcTemplate.queryForList(..., String::class.java, ...)` mapping. The test still checks the event status separately, so PostgreSQL no longer raises `IncorrectResultSetColumnCountException` for that query.
- Error responses now explicitly use `application/json`. `FactoryExceptionHandler` applies the JSON content type to its `FactoryException`, `IllegalArgumentException`, and generic exception responses. `ArtifactAdminMethodNotAllowedAdvice` applies it to 405 responses as well. This prevents an SSE endpoint’s `text/event-stream` negotiation from being reused for an `ErrorResponse`, avoiding the missing-converter failure.

## Files carrying the change

- `factory-service/src/main/kotlin/io/whozoss/factory/error/FactoryExceptionHandler.kt` — sets `MediaType.APPLICATION_JSON` on all handled error responses.
- `factory-service/src/main/kotlin/io/whozoss/factory/artifact/web/ArtifactAdminMethodNotAllowedAdvice.kt` — sets the same content type for unsupported artifact-admin methods.
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultServiceIntegrationTest.kt` — uses the single-column outbox query.
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowSseHttpTest.kt` — adds an HTTP-level regression test: an unauthorized SSE stream request must return `401`, JSON content type, and the expected `TRUST_CONTEXT_UNAVAILABLE` error envelope.

Normal stream behavior is covered by the existing SSE test in `WorkflowSseHttpTest`; the change only makes the error path explicitly JSON. Verify from `factory-service` with `./gradlew clean test`, or target the affected integration tests while iterating.
