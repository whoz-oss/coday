package io.whozoss.factory.web

import org.springframework.stereotype.Component

/**
 * Membership/entitlement data attached to a resolved principal. Never read from
 * client headers — always resolved server-side (see `membership-resolver.ts`).
 */
data class MembershipInfo(
    val organizationId: String? = null,
    val workstreamId: String? = null,
    val squadId: String? = null,
    val roles: List<String> = emptyList(),
) {
    companion object {
        val EMPTY: MembershipInfo = MembershipInfo()
    }
}

/** Resolves memberships for an authenticated principal, server-side. */
interface MembershipResolver {
    fun resolveMembership(principalId: String?, principalType: String): MembershipInfo
}

/**
 * In-memory membership directory for local development and tests.
 *
 * Fail-closed: an absent principal resolves to no membership at all. Otherwise
 * the synthetic default membership of the principal type is granted (mirrors
 * `LocalDevMembershipResolver` / `defaultMembershipFor` in the Node codebase).
 */
@Component
class LocalDevMembershipResolver : MembershipResolver {

    override fun resolveMembership(principalId: String?, principalType: String): MembershipInfo {
        if (principalId.isNullOrBlank()) return MembershipInfo.EMPTY
        return defaultMembershipFor(principalType)
    }

    companion object {
        fun defaultMembershipFor(principalType: String): MembershipInfo =
            MembershipInfo(
                organizationId = "org-local-dev",
                workstreamId = "ws-default",
                squadId = null,
                roles = when (principalType) {
                    TrustContext.PRINCIPAL_TYPE_SERVICE -> listOf(TrustContext.SERVICE_RUNNER_ROLE)
                    else -> listOf(TrustContext.FACTORY_MEMBER_ROLE)
                },
            )
    }
}
