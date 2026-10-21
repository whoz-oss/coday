package io.whozoss.factory.forge.web

import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.forge.port.JiraNotConfiguredException
import io.whozoss.factory.web.AuthenticatedUser
import java.time.Instant

/**
 * Plain Jira handler group: the `/api/jira/:ticketId` and
 * `/api/factory/jira/:ticketId` relay.
 *
 * Port of `factory/dashboard/composition-root.mjs`. Missing credentials raise
 * `501 JIRA_NOT_CONFIGURED` with the exact Node message. This class carries no
 * web-framework annotations; the PF4J route contributor maps requests onto
 * [ticket].
 */
class JiraProxyController(
    private val jira: JiraClient,
) {

    @Suppress("UNUSED_PARAMETER")
    fun ticket(ticketId: String, user: AuthenticatedUser): Map<String, Any?> {
        if (!jira.isConfigured()) throw JiraNotConfiguredException(jira.missingCredentials())
        val fetched = jira.fetchTicket(ticketId)
        return linkedMapOf(
            "ticketContent" to fetched.ticketContent,
            "summary" to fetched.summary,
            "epicKey" to fetched.epicKey,
            "epicSummary" to fetched.epicSummary,
            "fieldCount" to fetched.fieldCount,
            "commentCount" to fetched.commentCount,
            "commentsIncluded" to fetched.commentsIncluded,
            "commentsTruncated" to fetched.commentsTruncated,
            "fetchedAt" to Instant.now().toString(),
        )
    }
}
