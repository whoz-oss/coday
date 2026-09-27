package io.whozoss.factory.forge.port

/** Root fields a Jira ticket fetch returns (Markdown contract). */
data class JiraTicketFetch(
    val ticketContent: String,
    val summary: String,
    val epicKey: String?,
    val epicSummary: String?,
    val fieldCount: Int,
    val commentCount: Int,
    val commentsIncluded: Int,
    val commentsTruncated: Boolean,
)

/**
 * Jira integration port. Injected so the proxy controller stays testable and so
 * missing credentials surface as an explicit, wire-stable error.
 */
interface JiraClient {

    /** True when base URL, email and API token are all configured. */
    fun isConfigured(): Boolean

    /** The missing credential variable names (empty when configured). */
    fun missingCredentials(): List<String>

    /** Fetch a ticket and its comments as Markdown. Throws on any HTTP error. */
    fun fetchTicket(ticketId: String): JiraTicketFetch
}

/**
 * Raised when the dashboard has no Jira credentials configured.
 *
 * Mapped to `501 JIRA_NOT_CONFIGURED` with the exact Node error message.
 */
class JiraNotConfiguredException(missing: List<String>) : io.whozoss.factory.error.FactoryException(
    501,
    "JIRA_NOT_CONFIGURED",
    "Le serveur du dashboard n'a pas de credentials Jira configurés (manquant : ${missing.joinToString(", ")}). " +
        "Relancez-le avec ces variables dans son environnement : " +
        "JIRA_BASE_URL=https://votre-instance.atlassian.net " +
        "JIRA_EMAIL=votre@email.com " +
        "JIRA_API_TOKEN=votre-token " +
        "node factory/dashboard/server.mjs",
    mapOf("missing" to missing),
)

/** Raised when Jira answers an HTTP error — mapped to 502 `JIRA_UNAVAILABLE`. */
class JiraUnavailableException(message: String, cause: Throwable? = null) :
    io.whozoss.factory.error.FactoryException(502, "JIRA_UNAVAILABLE", message, null, cause)
