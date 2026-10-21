package io.whozoss.factory.persistence

import io.whozoss.factory.config.FactoryProperties
import org.springframework.stereotype.Component

/**
 * Provides the [TenantScope] for work that runs without a request (seeding,
 * migrations, health probes). A request's scope comes from its
 * [io.whozoss.factory.web.AuthenticatedUser], never from here.
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
}
