package io.whozoss.factory.proxy

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.web.factoryError
import io.whozoss.factory.web.resolveFactoryCaller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AgentOS relay endpoints.
 *
 * Port of the `/api/agents` and `/api/cases/:caseId/events` pass-throughs in
 * `factory/dashboard/composition-root.mjs`. The trusted `X-External-User-Id` is
 * propagated from the verified trust context.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "agentos-proxy", description = "AgentOS relay endpoints")
class AgentOsProxyController(
    private val proxy: AgentOsProxyClient,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(path = ["/namespaces"])
    @Operation(summary = "List AgentOS namespaces visible to the trusted caller.")
    fun namespaces(
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        return proxy.fetchNamespaces(caller.externalUserId)
    }

    @GetMapping(path = ["/agents"])
    @Operation(summary = "List AgentOS agent configs for a namespace.")
    fun agents(
        @RequestParam(name = "namespaceId", required = false) namespaceId: String?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        val ns = namespaceId?.takeIf { it.isNotBlank() }
            ?: factoryError(400, "MISSING_NAMESPACE_ID", "namespaceId requis")
        return proxy.fetchAgents(ns, caller.externalUserId)
    }

    @GetMapping(path = ["/cases/{caseId}/events"])
    @Operation(summary = "Relay the events of an AgentOS case.")
    fun caseEvents(
        @PathVariable caseId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Any? {
        val caller = resolveFactoryCaller(trustContext, tenantScopeProvider)
        return proxy.fetchCaseEvents(caseId, caller.externalUserId)
    }
}
