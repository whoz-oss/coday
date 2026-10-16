package io.whozoss.factory.agentattempt.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import io.whozoss.factory.agentattempt.service.AgentStepResultSubmission
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.agentattempt.persistence.DurableAttemptIntegrationBindingRepository
import io.whozoss.factory.error.ErrorResponse
import io.whozoss.factory.error.ForbiddenException
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.web.TrustContextExtractor
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
    private val durableAttempts: DurableAgentAttemptService,
    private val integrationBindings: DurableAttemptIntegrationBindingRepository,
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
            description = "RESULT_SEMANTIC_COLLISION, IDEMPOTENCY_KEY_COLLISION or STALE_AMENDMENT_SEQUENCE",
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
        @RequestHeader(name = "X-Idempotency-Key", required = false) legacyIdempotencyHeader: String?,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyHeader: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<AgentStepResultEnvelope<AgentStepResultData>> {
        val scope = tenantScopeProvider.scopeOf(trustContext) ?: throw TrustContextUnavailableException()
        val body = request ?: throw InvalidResultRequestException("A JSON body is required")
        val signedServiceRoute = authorization.isNullOrBlank() &&
            trustContext?.authenticationMethod == TrustContext.AUTH_PROXY_SIGNATURE &&
            trustContext.principalType == TrustContext.PRINCIPAL_TYPE_SERVICE
        if (signedServiceRoute) {
            return submitTrustedService(body, agentHeader, idempotencyHeader, trustContext, scope)
        }
        if (trustContext?.authenticationMethod == TrustContext.AUTH_STATIC_SERVICE_BEARER) {
            return submitHttpApiService(body, idempotencyHeader, trustContext, scope)
        }
        val token = bearerToken(authorization)
            ?: throw TrustContextUnavailableException("A bearer capability token is required")
        val business = foldAmendmentSeq(
            body.result ?: body.business,
            body.expected_amendment_seq ?: body.expectedAmendmentSeq,
        )
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
        val idempotencyKey = legacyIdempotencyHeader?.takeIf { it.isNotBlank() }
            ?: idempotencyHeader?.takeIf { it.isNotBlank() }
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

    private fun submitHttpApiService(
        body: AgentStepResultRequest,
        idempotencyHeader: String?,
        trustContext: TrustContext,
        scope: io.whozoss.factory.persistence.TenantScope,
    ): ResponseEntity<AgentStepResultEnvelope<AgentStepResultData>> {
        if (TrustContextExtractor.HTTP_API_SUBMISSION_SCOPE !in trustContext.scopes) {
            throw ForbiddenException("The service credential cannot submit Factory step results")
        }
        if (body.observed != null || body.caseId != null || body.agentName != null ||
            body.business != null || body.idempotencyKey != null
        ) {
            throw InvalidResultRequestException(
                "The HTTP_API service route accepts only 'attemptId', 'result', and an Idempotency-Key header",
            )
        }
        val attemptId = body.attemptId?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("'attemptId' is required")
        val result = foldAmendmentSeq(
            body.result ?: throw InvalidResultRequestException("'result' is required"),
            body.expected_amendment_seq ?: body.expectedAmendmentSeq,
        ) ?: throw InvalidResultRequestException("'result' is required")
        val idempotencyKey = idempotencyHeader?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("Idempotency-Key is required")
        if (idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidResultRequestException("'Idempotency-Key' must be at most $MAX_IDEMPOTENCY_KEY_LENGTH characters")
        }
        val serviceIdentityId = trustContext.serviceIdentityId?.takeIf { it.isNotBlank() }
            ?: throw ForbiddenException("The verified service identity is unavailable")
        val binding = integrationBindings.find(scope, attemptId, serviceIdentityId)
            ?: throw ForbiddenException("The service identity is not authorized for this Factory attempt")
        val attempt = durableAttempts.find(
            scope, binding.namespaceId, binding.workflowId, binding.stepId, binding.attemptId,
        ) ?: throw InvalidResultRequestException("The authorized durable attempt no longer exists")
        val persistedBriefHash = attempt.brief?.let(io.whozoss.factory.agentattempt.domain.CanonicalJsonHash::sha256)
        if (persistedBriefHash == null || persistedBriefHash != binding.briefHash) {
            throw ForbiddenException("The Factory attempt authorization no longer matches its frozen context")
        }
        val effectiveKey = "${attempt.attemptId}:$idempotencyKey"
        val submission = service.submitTrusted(scope, attempt, result, null, effectiveKey)
        return submissionResponse(submission)
    }

    private fun submitTrustedService(
        body: AgentStepResultRequest,
        agentHeader: String?,
        idempotencyHeader: String?,
        trustContext: TrustContext,
        scope: io.whozoss.factory.persistence.TenantScope,
    ): ResponseEntity<AgentStepResultEnvelope<AgentStepResultData>> {
        if (body.attemptId != null || body.observed != null || body.caseId != null ||
            body.agentName != null || body.business != null || body.idempotencyKey != null ||
            body.expectedAmendmentSeq != null || body.expected_amendment_seq != null
        ) {
            throw InvalidResultRequestException("The signed service route accepts only the 'result' field")
        }
        val result = body.result ?: throw InvalidResultRequestException("'result' is required")
        val namespaceId = trustContext.namespaceId?.takeIf { it.isNotBlank() }
            ?: throw TrustContextUnavailableException("A signed namespace is required")
        val caseId = trustContext.caseId?.takeIf { it.isNotBlank() }
            ?: throw TrustContextUnavailableException("A signed case is required")
        val idempotencyKey = idempotencyHeader?.takeIf { it.isNotBlank() }
            ?: throw InvalidResultRequestException("Idempotency-Key is required")
        if (idempotencyKey.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidResultRequestException("'Idempotency-Key' must be at most $MAX_IDEMPOTENCY_KEY_LENGTH characters")
        }
        val matches = durableAttempts.findActiveByCase(scope, namespaceId, caseId)
        if (matches.size != 1) {
            throw InvalidResultRequestException(
                if (matches.isEmpty()) "No active durable attempt matches the signed namespace and case"
                else "The signed namespace and case resolve to multiple active durable attempts",
            )
        }
        val attempt = matches.single()
        val suppliedAgent = agentHeader?.takeIf { it.isNotBlank() }
        if (suppliedAgent != null && suppliedAgent != attempt.agentName) {
            throw ResultIdentityMismatchException("X-AgentOS-Agent-Name does not match the durable attempt")
        }
        val submission = service.submitTrusted(scope, attempt, result, suppliedAgent, idempotencyKey)
        return submissionResponse(submission)
    }

    private fun submissionResponse(
        submission: AgentStepResultSubmission,
    ): ResponseEntity<AgentStepResultEnvelope<AgentStepResultData>> {
        val status = if (submission.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(
            AgentStepResultEnvelope(
                AgentStepResultData(submission.resultId, submission.idempotent, submission.resultHash),
            ),
        )
    }

    /**
     * Folds a top-level `expected_amendment_seq` into the business payload so the
     * amendment counter is part of the canonical, hashed result identity. An
     * explicit value already carried inside `business` wins, so a divergent
     * top-level field is never silently rewritten.
     */
    private fun foldAmendmentSeq(business: JsonNode?, seq: Long?): JsonNode? {
        if (business == null || seq == null || !business.isObject) return business
        val node = (business as ObjectNode).deepCopy()
        if (!node.has("expected_amendment_seq") && !node.has("expectedAmendmentSeq")) {
            node.put("expected_amendment_seq", seq)
        }
        return node
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
