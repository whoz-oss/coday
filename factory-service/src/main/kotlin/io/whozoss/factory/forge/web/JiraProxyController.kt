package io.whozoss.factory.forge.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.forge.port.JiraNotConfiguredException
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Jira ticket relay endpoints.
 *
 * Port of the `/api/jira/:ticketId` and `/api/factory/jira/:ticketId` routes in
 * `factory/dashboard/composition-root.mjs`. Missing credentials raise
 * `501 JIRA_NOT_CONFIGURED` with the exact Node message.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "jira", description = "Jira ticket relay")
class JiraProxyController(
    private val jira: JiraClient,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(path = ["/jira/{ticketId}", "/factory/jira/{ticketId}"])
    @Operation(summary = "Fetch a Jira ticket as Markdown.")
    fun ticket(
        @PathVariable ticketId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): Map<String, Any?> {
        resolveForgeCaller(trustContext, tenantScopeProvider)
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
