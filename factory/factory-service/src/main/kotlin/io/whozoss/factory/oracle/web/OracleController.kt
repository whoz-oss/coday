package io.whozoss.factory.oracle.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.error.UnauthenticatedException
import io.whozoss.factory.oracle.domain.InvalidOracleRunRequestException
import io.whozoss.factory.oracle.service.OracleExecutionService
import io.whozoss.factory.oracle.service.OracleRunCommand
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP boundary of the ORACLES aggregate.
 *
 * Port of `handleWorkflowOracleRequest` in
 * `factory/dashboard/workflow-oracle-routes.mjs`:
 * `POST /api/factory/workflows/{workflowId}/steps/{stepId}/oracles/{oracleId}/runs`.
 *
 * The call is scoped by the verified [TrustContext] resolved at the HTTP
 * boundary (never by client headers). Success responses use the canonical
 * `{ "data": ... }` envelope; failures are rendered by
 * [io.whozoss.factory.error.FactoryExceptionHandler] as
 * `{ "error": { "code", "message", "details" } }`.
 */
@RestController
@RequestMapping("/api/factory/workflows")
@Tag(name = "oracles", description = "Oracle definitions and execution runs")
class OracleController(
    private val service: OracleExecutionService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @PostMapping(
        path = ["/{workflowId}/steps/{stepId}/oracles/{oracleId}/runs"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(
        summary = "Run an oracle against a workflow step",
        description = "Starts (or idempotently replays) one oracle execution for the given step.",
    )
    fun run(
        @PathVariable workflowId: String,
        @PathVariable stepId: String,
        @PathVariable oracleId: String,
        @RequestBody(required = false) request: OracleRunRequest?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKeyHeader: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<DataEnvelope<OracleRunResponse>> {
        val scope = tenantScopeProvider.scopeOf(trustContext) ?: throw UnauthenticatedException()

        val body = request ?: throw InvalidOracleRunRequestException("A JSON body with 'namespaceId' is required")
        val namespaceId = body.namespaceId?.trim().orEmpty()
        if (namespaceId.isEmpty()) {
            throw InvalidOracleRunRequestException("'namespaceId' is required")
        }
        val idempotencyKey = body.idempotencyKey?.takeIf { it.isNotBlank() }
            ?: idempotencyKeyHeader?.takeIf { it.isNotBlank() }
        if (idempotencyKey != null && idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidOracleRunRequestException(
                "'idempotencyKey' must be at most $MAX_IDEMPOTENCY_KEY_LENGTH characters",
            )
        }

        val result = service.run(
            scope = scope,
            command = OracleRunCommand(
                workflowId = workflowId,
                stepId = stepId,
                oracleId = oracleId,
                namespaceId = namespaceId,
                idempotencyKey = idempotencyKey,
            ),
        )
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(DataEnvelope(result.toResponse()))
    }

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
    }
}
