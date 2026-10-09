package io.whozoss.factory.agentattempt.web

import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.InvalidResultRequestException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityRefreshForbiddenException
import io.whozoss.factory.agentattempt.domain.ResultIdentityMismatchException
import io.whozoss.factory.agentattempt.domain.TrustContextUnavailableException
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Server-to-server renewal of an unconsumed result capability. */
@RestController
@RequestMapping("/api/factory/agent-step-results/capability")
class AgentStepResultRefreshController(
    private val service: AgentStepResultService,
    private val tenantScopeProvider: TenantScopeProvider,
) {
    data class Request(val attemptId: String?, val runtimeId: String?, val agentName: String?)
    data class Data(val attemptId: String, val capabilityToken: String, val expiresAt: String)
    data class Envelope<T>(val data: T)

    @PostMapping("/refresh", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun refresh(
        @RequestBody(required = false) request: Request?,
        trustContext: TrustContext?,
    ): Envelope<Data> {
        val trust = trustContext ?: throw TrustContextUnavailableException()
        if (!trust.authenticated || trust.principalType != TrustContext.PRINCIPAL_TYPE_SERVICE ||
            trust.authenticationMethod != TrustContext.AUTH_PROXY_SIGNATURE ||
            REQUIRED_SCOPE !in trust.scopes
        ) {
            throw ResultCapabilityRefreshForbiddenException()
        }
        val scope = tenantScopeProvider.scopeOf(trust) ?: throw TrustContextUnavailableException()
        val body = request ?: throw InvalidResultRequestException("A JSON body is required")
        val attemptId = body.attemptId?.takeIf { it.isNotBlank() } ?: throw InvalidResultRequestException("'attemptId' is required")
        val runtimeId = body.runtimeId?.takeIf { it.isNotBlank() } ?: throw InvalidResultRequestException("'runtimeId' is required")
        val agentName = body.agentName?.takeIf { it.isNotBlank() } ?: throw InvalidResultRequestException("'agentName' is required")
        val namespaceId = trust.namespaceId?.takeIf { it.isNotBlank() } ?: throw ResultIdentityMismatchException()
        val caseId = trust.caseId?.takeIf { it.isNotBlank() } ?: throw ResultIdentityMismatchException()
        val issued = service.refresh(
            scope,
            AgentStepResultObservedIdentity(attemptId, caseId, agentName, namespaceId),
            runtimeId,
            "$runtimeId|$caseId|$attemptId|$agentName",
        )
        return Envelope(Data(attemptId, issued.token, issued.expiresAt))
    }

    companion object {
        const val REQUIRED_SCOPE = "workflow:write"
    }
}
