package io.whozoss.factory.agentattempt.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.InvalidResultRequestException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.TrustContextUnavailableException
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.error.ErrorResponse
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP boundary of the AGENT-STEP aggregate.
 *
 * Faithful port of `handleAgentStepResultRequest` in
 * `factory/dashboard/agent-step-result-routes.mjs`:
 * `POST /api/factory/agent-step-results`.
 *
 * The caller presents an `Authorization: Bearer <capability>` token issued for
 * exactly one attempt; the tenant scope comes from the verified [TrustContext]
 * resolved at the HTTP boundary. Success responses use the canonical
 * `{ "data": ... }` envelope; failures are rendered by
 * [io.whozoss.factory.error.FactoryExceptionHandler] as
 * `{ "error": { "code", "message", "details" } }`.
 */
@RestController
@RequestMapping("/api/factory/agent-step-results")
@Tag(name = "agent-step-results", description = "Capability-bound agent step result submission")
class AgentStepResultController(
    private val service: AgentStepResultService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Submit a capability-bound agent step result.",
        description = "Requires an `Authorization: Bearer <capability>` token issued by the Factory.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Result created"),
        ApiResponse(responseCode = "200", description = "Idempotent replay of an identical submission"),
        ApiResponse(
            responseCode = "400",
            description = "RESULT_SCHEMA_INVALID, RESULT_IDENTITY_MISMATCH or INVALID_RESULT_REQUEST",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "RESULT_CAPABILITY_INVALID or TRUST_CONTEXT_UNAVAILABLE",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "RESULT_SEMANTIC_COLLISION or IDEMPOTENCY_KEY_COLLISION",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "410",
            description = "RESULT_CAPABILITY_EXPIRED",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun submit(
        @RequestBody(required = false) request: AgentStepResultRequest?,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestHeader(name = "X-AgentOS-Case-Id", required = false) caseHeader: String?,
        @RequestHeader(name = "X-AgentOS-Agent-Name", required = false) agentHeader: String?,
        @RequestHeader(name = "X-Idempotency-Key", required = false) idempotencyHeader: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<AgentStepResultEnvelope<AgentStepResultData>> {
        val scope = tenantScopeProvider.scopeOf(trustContext) ?: throw TrustContextUnavailableException()
        val token = bearerToken(authorization)
            ?: throw TrustContextUnavailableException("A bearer capability token is required")

        val body = request ?: throw InvalidResultRequestException("A JSON body is required")
        val business = body.result ?: body.business
        val attemptId = body.observed?.attemptId?.takeIf { it.isNotBlank() }
            ?: body.attemptId?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("'attemptId' is required")
        val declaredCaseId = body.observed?.caseId?.takeIf { it.isNotBlank() }
            ?: body.caseId?.takeIf { it.isNotBlank() }
            ?: caseHeader?.takeIf { it.isNotBlank() }
        val agentName = body.observed?.agentName?.takeIf { it.isNotBlank() }
            ?: body.agentName?.takeIf { it.isNotBlank() }
            ?: agentHeader?.takeIf { it.isNotBlank() }

        // Identity fencing anchored on the trust boundary: when the verified
        // TrustContext carries a case/namespace identity (signed JWT claims, or
        // the loopback-dev headers on a local socket), it OVERRIDES whatever
        // the body declares. A body that contradicts the trusted identity is
        // rejected outright; a body that omits it inherits the trusted value.
        val trustedNamespaceId = trustContext?.namespaceId?.takeIf { it.isNotBlank() }
        val trustedCaseId = trustContext?.caseId?.takeIf { it.isNotBlank() }
        if (trustedCaseId != null && declaredCaseId != null && declaredCaseId != trustedCaseId) {
            throw ResultIdentityMismatchException(
                "The declared caseId does not match the trusted execution context",
            )
        }
        val caseId = trustedCaseId ?: declaredCaseId
        val idempotencyKey = idempotencyHeader?.takeIf { it.isNotBlank() }
            ?: body.idempotencyKey?.takeIf { it.isNotBlank() }
        if (idempotencyKey != null && idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidResultRequestException(
                "'X-Idempotency-Key' must be at most $MAX_IDEMPOTENCY_KEY_LENGTH characters",
            )
        }

        val submission = service.submit(
            scope = scope,
            token = token,
            business = business,
            observed = AgentStepResultObservedIdentity(
                attemptId = attemptId,
                caseId = caseId,
                agentName = agentName,
                namespaceId = trustedNamespaceId,
            ),
            idempotencyKey = idempotencyKey,
        )
        val status = if (submission.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(
            AgentStepResultEnvelope(
                AgentStepResultData(
                    resultId = submission.resultId,
                    idempotent = submission.idempotent,
                    resultHash = submission.resultHash,
                ),
            ),
        )
    }

    private fun bearerToken(authorization: String?): String? {
        if (authorization.isNullOrBlank()) return null
        val match = BEARER_PATTERN.find(authorization) ?: return null
        return match.groupValues[1].trim().ifEmpty { null }
    }

    private companion object {
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 128
        val BEARER_PATTERN = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)
    }
}
