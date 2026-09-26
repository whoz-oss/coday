package io.whozoss.factory.web

/**
 * The identity + security context resolved once at the HTTP boundary and
 * propagated to every handler.
 *
 * Vocabulary matches `factory/src/domain/identity/trust-context.ts`. All fields
 * are always present (no `undefined`) so downstream handlers can reason about
 * identity without defensive defaults.
 */
data class TrustContext(
    val principalId: String? = null,
    val principalType: String = PRINCIPAL_TYPE_HUMAN,
    val organizationId: String? = null,
    val workstreamId: String? = null,
    val squadId: String? = null,
    val roles: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
    val correlationId: String? = null,
    val authenticationMethod: String = AUTH_ANONYMOUS,
    val serviceIdentityId: String? = null,
    val loopback: Boolean = false,
) {
    /** `false` only for an explicit `anonymous` context. */
    val authenticated: Boolean
        get() = authenticationMethod != AUTH_ANONYMOUS

    companion object {
        const val AUTH_JWT = "jwt"
        const val AUTH_PROXY_SIGNATURE = "proxy-signature"
        const val AUTH_LOOPBACK_DEV = "loopback-dev"
        const val AUTH_ANONYMOUS = "anonymous"

        const val PRINCIPAL_TYPE_HUMAN = "human"
        const val PRINCIPAL_TYPE_SERVICE = "service"

        /** Principal id attributed to unattended local loopback development requests. */
        const val LOOPBACK_DEV_PRINCIPAL_ID = "local-dev-user"

        /** Factory roles (AgentOS directory vocabulary mapped to Factory entitlements). */
        const val FACTORY_ADMIN_ROLE = "admin"
        const val FACTORY_MEMBER_ROLE = "dev"
        const val SERVICE_RUNNER_ROLE = "service-runner"

        /** Explicit admin scope granted by a privileged credential. */
        const val ADMIN_SCOPE = "admin:*"

        /** Loopback-dev wildcard scope. */
        const val ADMIN_WILDCARD_SCOPE = "*"

        fun anonymous(correlationId: String? = null, loopback: Boolean = false): TrustContext =
            TrustContext(
                authenticationMethod = AUTH_ANONYMOUS,
                correlationId = correlationId,
                loopback = loopback,
            )

        fun isKnownPrincipalType(value: String?): Boolean =
            value == PRINCIPAL_TYPE_HUMAN || value == PRINCIPAL_TYPE_SERVICE
    }
}
