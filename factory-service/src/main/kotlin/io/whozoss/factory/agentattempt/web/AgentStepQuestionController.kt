package io.whozoss.factory.agentattempt.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.agentattempt.domain.InvalidResultRequestException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.TrustContextUnavailableException
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
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
 * HTTP boundary of the Phase 4 ask-step-question channel:
 * `POST /api/factory/agent-step-questions`.
 *
 * Sibling of [AgentStepResultController], deliberately a DEDICATED endpoint:
 * a step question is not a terminal business verdict, so it must not flow
 * through the single-use `PASS`/`FAIL` result contract. The caller presents
 * the same `Authorization: Bearer <capability>` token issued for exactly one
 * attempt; the capability is resolved READ-ONLY (never redeemed) to bind the
 * question to the durable attempt.
 *
 * The endpoint returns `202` as soon as the question is durably recorded and
 * the attempt parks in `waiting_human`: the worker call is never held open
 * awaiting the human answer. Success responses use the canonical
 * `{ "data": ... }` envelope; failures are rendered by
 * [io.whozoss.factory.error.FactoryExceptionHandler] as
 * `{ "error": { "code", "message", "details" } }`.
 */
@RestController
@RequestMapping("/api/factory/agent-step-questions")
@Tag(name = "agent-step-questions", description = "Capability-bound worker step questions (ask-step-question)")
class AgentStepQuestionController(
    private val service: AgentStepQuestionService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Ask a durable human step question and park the attempt in waiting_human.",
        description = "Requires an `Authorization: Bearer <capability>` token issued by the Factory. " +
            "Returns as soon as the question is durably recorded — the answer never blocks the worker call.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Question durably recorded (or idempotent replay)"),
        ApiResponse(
            responseCode = "400",
            description = "QUESTION_SCHEMA_INVALID, RESULT_IDENTITY_MISMATCH or INVALID_RESULT_REQUEST",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "RESULT_CAPABILITY_INVALID or TRUST_CONTEXT_UNAVAILABLE",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "QUESTION_ATTEMPT_NOT_WAITABLE or QUESTION_ALREADY_ASKED",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "410",
            description = "RESULT_CAPABILITY_EXPIRED",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun ask(
        @RequestBody(required = false) request: AgentStepQuestionRequest?,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestHeader(name = "X-AgentOS-Case-Id", required = false) caseHeader: String?,
        @RequestHeader(name = "X-AgentOS-Agent-Name", required = false) agentHeader: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<AgentStepQuestionEnvelope<AgentStepQuestionData>> {
        val scope = tenantScopeProvider.scopeOf(trustContext) ?: throw TrustContextUnavailableException()
        val token = bearerToken(authorization)
            ?: throw TrustContextUnavailableException("A bearer capability token is required")

        val body = request ?: throw InvalidResultRequestException("A JSON body is required")
        val attemptId = body.attemptId?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("'attemptId' is required")

        // Identity fencing anchored on the trust boundary, exactly like the
        // step-result channel: a trusted case identity OVERRIDES the declared
        // one, and a contradiction is rejected outright.
        val trustedCaseId = trustContext?.caseId?.takeIf { it.isNotBlank() }
        val declaredCaseId = caseHeader?.takeIf { it.isNotBlank() }
        if (trustedCaseId != null && declaredCaseId != null && declaredCaseId != trustedCaseId) {
            throw ResultIdentityMismatchException(
                "The declared caseId does not match the trusted execution context",
            )
        }
        val asked = service.ask(
            scope = scope,
            token = token,
            attemptId = attemptId,
            questionNode = body.question,
            observedCaseId = trustedCaseId ?: declaredCaseId,
            observedAgentName = agentHeader?.takeIf { it.isNotBlank() },
            observedNamespaceId = trustContext?.namespaceId?.takeIf { it.isNotBlank() },
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            AgentStepQuestionEnvelope(
                AgentStepQuestionData(
                    attemptId = asked.attemptId,
                    interactionId = asked.interactionId,
                    status = asked.status,
                    idempotent = asked.idempotent,
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
        val BEARER_PATTERN = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)
    }
}
