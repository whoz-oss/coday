# Implementation Plan - Fix macOS/PostgreSQL Test Failures & SSE Error Response Formatting

This plan addresses two test failure / error-handling edge cases in `factory-service`:
1. `IncorrectResultSetColumnCountException` at `AgentStepResultServiceIntegrationTest.kt:96` when querying PostgreSQL outbox events.
2. `HttpMessageNotWritableException` when an exception is thrown on an SSE endpoint (e.g., `GET /api/factory/workflows/stream`), ensuring `FactoryExceptionHandler` explicitly sets `Content-Type: application/json` so Spring MVC serializes error responses properly without crashing or throwing converter errors.

---

## User Review Required

> [!IMPORTANT]
> No breaking changes to existing APIs or contracts are introduced. All standard SSE event stream headers and behavior (`workflow-projection-*`, heartbeat framing, `text/event-stream`) remain identical for normal event streams.

---

## Proposed Changes

### Issue 1: `AgentStepResultServiceIntegrationTest.kt` Query Fix

**File to modify:**
- `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultServiceIntegrationTest.kt`

**Context:**
Line 96 currently reads:
```kotlin
val outbox = jdbcTemplate.queryForList(
    "SELECT event_type, status FROM outbox_events WHERE organization_id = ?",
    String::class.java,
    ORGANIZATION_ID,
)
```
Spring JDBC's `queryForList(sql, String::class.java, ...)` expects 1 single column per row in the SELECT statement. When 2 columns (`event_type, status`) are selected, `queryForList` throws `IncorrectResultSetColumnCountException: Expected column count 1`.

**Fix:**
Change the query line to select only `event_type`:
```kotlin
val outbox = jdbcTemplate.queryForList(
    "SELECT event_type FROM outbox_events WHERE organization_id = ?",
    String::class.java,
    ORGANIZATION_ID,
)
```
*Note:* The assertions that immediately follow already assert `outbox` has size 1, and then separately assert `event_type` and `status` via `queryForObject`. Updating `outbox` to query `SELECT event_type FROM outbox_events WHERE organization_id = ?` ensures `queryForList` returns `List<String>` cleanly without column count mismatch.

---

### Issue 2: Explicit Content-Type on `FactoryExceptionHandler` Error Responses

**Files to modify:**
- `factory-service/src/main/kotlin/io/whozoss/factory/error/FactoryExceptionHandler.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/artifact/web/ArtifactAdminMethodNotAllowedAdvice.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/workflow/WorkflowSseHttpTest.kt`

**Context:**
When an endpoint defines `produces = [MediaType.TEXT_EVENT_STREAM_VALUE]` (or client sends `Accept: text/event-stream`), Spring MVC inherits or negotiates `text/event-stream` for error responses produced by `@ExceptionHandler` methods if no `contentType` header is set on the returned `ResponseEntity`. Because no HTTP message converter converts `ErrorResponse` to `text/event-stream`, Spring throws `HttpMessageNotWritableException`.

**Fix in `FactoryExceptionHandler.kt`:**
Explicitly set `contentType(MediaType.APPLICATION_JSON)` on all `ResponseEntity` builder calls:
```kotlin
@ExceptionHandler(FactoryException::class)
fun handleFactoryException(exception: FactoryException): ResponseEntity<ErrorResponse> {
    logger.debug { "Handled FactoryException ${exception.errorCode} -> ${exception.statusCode}" }
    return ResponseEntity
        .status(exception.statusCode)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            ErrorResponse(
                ErrorDetail(
                    code = exception.errorCode,
                    message = exception.message ?: exception.errorCode,
                    details = exception.details,
                ),
            ),
        )
}

@ExceptionHandler(IllegalArgumentException::class)
fun handleIllegalArgumentException(exception: IllegalArgumentException): ResponseEntity<ErrorResponse> =
    ResponseEntity
        .status(400)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            ErrorResponse(
                ErrorDetail(
                    code = "BAD_REQUEST",
                    message = exception.message ?: "Invalid argument",
                    details = null,
                ),
            ),
        )

@ExceptionHandler(Exception::class)
fun handleGenericException(exception: Exception): ResponseEntity<ErrorResponse> {
    logger.error(exception) { "Unhandled exception" }
    return ResponseEntity
        .status(500)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            ErrorResponse(
                ErrorDetail(
                    code = "INTERNAL_ERROR",
                    message = exception.message ?: "Internal server error",
                    details = null,
                ),
            ),
        )
}
```

**Fix in `ArtifactAdminMethodNotAllowedAdvice.kt`:**
Similarly, set `.contentType(MediaType.APPLICATION_JSON)` on its `ResponseEntity`:
```kotlin
@ExceptionHandler(HttpRequestMethodNotSupportedException::class)
fun handleMethodNotSupported(exception: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> =
    ResponseEntity
        .status(405)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            ErrorResponse(
                ErrorDetail(
                    code = "METHOD_NOT_ALLOWED",
                    message = "Admin artifact commands require POST",
                    details = null,
                ),
            ),
        )
```

**Verification / Test Extension in `WorkflowSseHttpTest.kt`:**
Add a test in `WorkflowSseHttpTest.kt` (or `WorkflowSseHttpTest` subclass/method) that specifically requests the SSE stream endpoint without valid parameters or with an error condition (or calling SSE endpoint with missing/invalid params), asserting:
1. Status code is 4xx or 5xx (e.g., 401 UNAUTHORIZED or 400 BAD_REQUEST when namespaceId/trustContext is invalid or throwing an error).
2. Content-Type header on error response is `application/json` (or `application/json;charset=UTF-8`).
3. Response body matches the standard `ErrorResponse` JSON structure `{ "error": { "code": "...", "message": "..." } }`.
4. No `HttpMessageNotWritableException` or `AsyncRequestTimeoutException` is thrown.

---

## Verification Plan

### Automated Tests
1. Run target test suite:
   ```bash
   pnpm nx test factory-service -- --rerun-tasks
   ```
2. Specifically run integration tests for modified areas:
   - `AgentStepResultServiceIntegrationTest`
   - `WorkflowSseHttpTest`
   - `HttpBoundaryIntegrationTest`

### Quality Checks
```bash
pnpm nx affected -t lint --base="$(cat /work/data/baseline 2>/dev/null || echo 'HEAD~1')"
```

---

## Step-by-Step Implementation Outline

1. Update `factory-service/src/test/kotlin/io/whozoss/factory/agentattempt/AgentStepResultServiceIntegrationTest.kt`:
   - Line 96: Change `"SELECT event_type, status FROM outbox_events WHERE organization_id = ?"` to `"SELECT event_type FROM outbox_events WHERE organization_id = ?"`.
2. Update `factory-service/src/main/kotlin/io/whozoss/factory/error/FactoryExceptionHandler.kt`:
   - Import `org.springframework.http.MediaType`.
   - Add `.contentType(MediaType.APPLICATION_JSON)` to all `ResponseEntity` responses in `@ExceptionHandler` methods.
3. Update `factory-service/src/main/kotlin/io/whozoss/factory/artifact/web/ArtifactAdminMethodNotAllowedAdvice.kt`:
   - Import `org.springframework.http.MediaType`.
   - Add `.contentType(MediaType.APPLICATION_JSON)` to `handleMethodNotSupported`.
4. Add test case to `WorkflowSseHttpTest.kt`:
   - Assert SSE error scenarios (e.g., when calling `GET /api/factory/workflows/stream` with invalid/missing headers or causing an exception) return HTTP error status with `Content-Type: application/json` and standard error JSON payload without `HttpMessageNotWritableException`.
5. Run tests:
   - `pnpm nx test factory-service -- --rerun-tasks`
