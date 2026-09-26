package io.whozoss.factory.web

import io.whozoss.factory.error.ForbiddenAdminRequiredException
import org.springframework.stereotype.Component

/** Structured admin decision, mirroring the Node guard contract. */
data class AdminAuthorizationDecision(
    val authorized: Boolean,
    val reason: String? = null,
)

/**
 * The single, named authorization point every admin use case must pass through.
 *
 * Port of `checkAdminAuthorization` / `requireAdminRole` in
 * `factory/dashboard/http-utils.mjs`. The principal passes only when it carries
 * the AgentOS-derived `admin` role, the explicit `admin:*` scope, or the
 * loopback-dev wildcard `*` scope. An anonymous, missing or member-only context
 * fails closed.
 */
@Component
class AdminGuard {

    fun checkAdminAuthorization(trustContext: TrustContext?): AdminAuthorizationDecision {
        if (trustContext == null) return AdminAuthorizationDecision(false, MISSING_TRUST_CONTEXT)
        if (!trustContext.authenticated) return AdminAuthorizationDecision(false, UNAUTHENTICATED)
        val roles = normalizeRoles(trustContext.roles)
        val isAdmin = roles.contains(TrustContext.FACTORY_ADMIN_ROLE) ||
            trustContext.scopes.contains(TrustContext.ADMIN_SCOPE) ||
            trustContext.scopes.contains(TrustContext.ADMIN_WILDCARD_SCOPE)
        return if (isAdmin) {
            AdminAuthorizationDecision(true, null)
        } else {
            AdminAuthorizationDecision(false, INSUFFICIENT_ADMIN_PERMISSIONS)
        }
    }

    /**
     * Enforce [checkAdminAuthorization], throwing a transport-ready 403
     * (`FORBIDDEN_ADMIN_REQUIRED`) when the principal is not an admin.
     */
    fun requireAdminRole(trustContext: TrustContext?): Boolean {
        val check = checkAdminAuthorization(trustContext)
        if (!check.authorized) {
            throw ForbiddenAdminRequiredException("Admin authorization required (${check.reason})")
        }
        return true
    }

    companion object {
        /** Stable, machine-readable reasons an admin decision can be refused. */
        const val MISSING_TRUST_CONTEXT = "MISSING_TRUST_CONTEXT"
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val INSUFFICIENT_ADMIN_PERMISSIONS = "INSUFFICIENT_ADMIN_PERMISSIONS"

        /**
         * Map a single AgentOS role/group token to a Factory role:
         * `ADMIN`/`ADMINISTRATOR` -> `admin`; `MEMBER`/`DEV`/`DEVELOPER` -> `dev`;
         * anything else -> lowercased token. Mirrors `normalizeAgentOsRole`.
         */
        fun normalizeRole(raw: String): String? {
            val token = raw.trim().lowercase()
            if (token.isEmpty()) return null
            return when (token) {
                "admin", "administrator" -> TrustContext.FACTORY_ADMIN_ROLE
                "member", "dev", "developer" -> TrustContext.FACTORY_MEMBER_ROLE
                else -> token
            }
        }

        fun normalizeRoles(input: List<String>): List<String> =
            input.mapNotNull { normalizeRole(it) }.distinct()
    }
}
