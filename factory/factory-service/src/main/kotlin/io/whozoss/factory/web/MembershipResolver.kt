package io.whozoss.factory.web

import org.springframework.stereotype.Component

/** Resolves the organization trusted for an authenticated user. */
interface MembershipResolver {
    fun resolveOrganizationId(userId: String): String
}

/** Local development identity belongs to one explicit organization only. */
@Component
class LocalDevMembershipResolver : MembershipResolver {
    override fun resolveOrganizationId(userId: String): String = LOCAL_DEV_ORGANIZATION_ID

    companion object {
        const val LOCAL_DEV_ORGANIZATION_ID = "org-local-dev"
    }
}
