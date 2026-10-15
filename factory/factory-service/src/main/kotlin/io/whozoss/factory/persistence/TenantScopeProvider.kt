package io.whozoss.factory.persistence

import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.web.TrustContext
import org.springframework.stereotype.Component

/**
 * Resolves the [TenantScope] a repository operation must run in.
 *
 * The scope is derived from the *verified* [TrustContext] produced at the HTTP
 * boundary — never from client headers and never from an implicit default for a
 * remote caller. [defaultScope] is exposed only for local/bootstrap tooling that
 * runs without a request (seeding, migrations, health probes).
 */
@Component
class TenantScopeProvider(
    private val properties: FactoryProperties,
) {

    /** Scope from the configured `factory.tenant.*` defaults (bootstrap/no-request use). */
    fun defaultScope(): TenantScope =
        TenantScope(
            organizationId = properties.tenant.organizationId,
            workstreamId = properties.tenant.workstreamId,
        )

    /**
     * Fail-closed scope resolution from a trust context: an anonymous caller, a
     * missing context or a blank id yields `null`.
     */
    fun scopeOf(trustContext: TrustContext?): TenantScope? {
        if (trustContext == null || !trustContext.authenticated) return null
        val organizationId = trustContext.organizationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val workstreamId = trustContext.workstreamId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TenantScope(organizationId, workstreamId)
    }
}
