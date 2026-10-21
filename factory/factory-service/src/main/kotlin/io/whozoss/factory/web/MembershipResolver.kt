package io.whozoss.factory.web

import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Component

/**
 * Resolves, server-side, the tenant a user belongs to. It is the only source of a
 * user's [TenantScope]: nothing is read from client input.
 */
interface MembershipResolver {
    fun resolveMembership(userId: String): TenantScope
}

/**
 * In-memory membership directory for local development and tests: every user
 * belongs to the synthetic `org-local-dev` / `ws-default` tenant.
 */
@Component
class LocalDevMembershipResolver : MembershipResolver {

    override fun resolveMembership(userId: String): TenantScope = LOCAL_DEV_SCOPE

    companion object {
        val LOCAL_DEV_SCOPE = TenantScope("org-local-dev", "ws-default")
    }
}
