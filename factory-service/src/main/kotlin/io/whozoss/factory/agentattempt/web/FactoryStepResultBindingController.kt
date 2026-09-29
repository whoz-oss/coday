package io.whozoss.factory.agentattempt.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.agentattempt.domain.ResultCapabilityInvalidException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.TrustContextUnavailableException
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.error.ErrorResponse
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only HTTP boundary of the step-result *binding* channel.
 *
 * The Factory mints a single-use submission capability when an agent step starts
 * and transmits it (with the attempt id) to AgentOS at case creation. This
 * endpoint lets a trusted caller verify a presented capability token and resolve
 * the attempt identity it is bound to **without redeeming it** — submission still
 * flows through [AgentStepResultController].
 *
 * The tenant scope comes from the verified [TrustContext]; the token is never
 * echoed back and the persisted digest is never exposed.
 */
@RestController
@RequestMapping("/api/factory/step-result-bindings")
@Tag(name = "step-result-bindings", description = "Verify a capability-bound agent step result submission")
class FactoryStepResultBindingController(
    private val service: AgentStepResultService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @PostMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Verify a step-result capability and resolve its binding.",
        description = "Requires an `Authorization: Bearer <capability>` token (or the token in the body). " +
            "Never redeems the capability.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The capability is valid and the binding is resolved"),
        ApiResponse(
            responseCode = "401",
            description = "RESULT_CAPABILITY_INVALID or TRUST_CONTEXT_UNAVAILABLE",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "RESULT_IDENTITY_MISMATCH",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    fun verify(
        @RequestBody(required = false) request: FactoryStepResultBindingRequest?,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<FactoryStepResultBindingEnvelope<FactoryStepResultBindingData>> {
        val scope = tenantScopeProvider.scopeOf(trustContext) ?: throw TrustContextUnavailableException()
        val token = request?.capabilityToken?.takeIf { it.isNotBlank() }
            ?: bearerToken(authorization)
            ?: throw TrustContextUnavailableException("A bearer capability token is required")
        val capability = service.resolveCapability(scope, token)
            ?: throw ResultCapabilityInvalidException()

        val declaredAttemptId = request?.attemptId?.takeIf { it.isNotBlank() }
        if (declaredAttemptId != null && declaredAttemptId != capability.attemptId) {
            throw ResultIdentityMismatchException(
                "The declared attemptId does not match the capability binding",
            )
        }

        return ResponseEntity.ok(
            FactoryStepResultBindingEnvelope(
                FactoryStepResultBindingData(
                    attemptId = capability.attemptId,
                    workflowId = capability.workflowId,
                    stepId = capability.stepId,
                    namespaceId = capability.namespaceId,
                    caseId = capability.caseId,
                    agentName = capability.agentName,
                    issuedAt = capability.issuedAt,
                    expiresAt = capability.expiresAt,
                    submissionBudget = capability.submissionBudget,
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
