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
 * Jira integration port. Injected so the proxy controller and the BMAD
 * lifecycle observer stay testable and so missing credentials surface as an
 * explicit, wire-stable error on proxy reads while lifecycle sync degrades
 * safely without blocking runs (Lot 6).
 */
interface JiraClient {

    /** True when base URL, email and API token are all configured. */
    fun isConfigured(): Boolean

    /** The missing credential variable names (empty when configured). */
    fun missingCredentials(): List<String>

    /** Fetch a ticket and its comments as Markdown. Throws on any HTTP error. */
    fun fetchTicket(ticketId: String): JiraTicketFetch

    /**
     * Posts a lifecycle comment onto a Jira ticket (Lot 6).
     * Default implementation is a no-op so read-only test doubles stay compatible.
     */
    fun postComment(ticketId: String, markdownBody: String): Boolean = false

    /**
     * Requests a Jira status transition by target status name or transition id
     * (Lot 6). Returns `true` if a matching transition was applied.
     */
    fun transitionTicket(ticketId: String, targetStatusOrTransitionName: String): Boolean = false
}

/**
 * Base exception for plugin HTTP handlers, independent of `factory-service`
 * internal exception classes.
 */
open class ForgePluginHttpException(
    val statusCode: Int,
    val errorCode: String,
    override val message: String,
    val details: Any? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when a plugin route requires an authenticated caller identity and none
 * was resolved by the host HTTP boundary.
 */
class ForgeUnauthenticatedException(
    message: String = "Authentication required",
) : ForgePluginHttpException(401, "UNAUTHENTICATED", message)

/**
 * Raised when the dashboard has no Jira credentials configured.
 *
 * Mapped to `501 JIRA_NOT_CONFIGURED` with the exact Node error message.
 */
class JiraNotConfiguredException(missing: List<String>) : ForgePluginHttpException(
    501,
    "JIRA_NOT_CONFIGURED",
    "Le serveur du dashboard n'a pas de credentials Jira configurés (manquant : ${missing.joinToString(", ")}). " +
        "Relancez-le avec ces variables dans son environnement : " +
        "JIRA_BASE_URL=https://votre-instance.atlassian.net " +
        "JIRA_EMAIL=votre@email.com " +
        "JIRA_API_TOKEN=votre-token " +
        "puis redémarrez factory-service (port 8141).",
    mapOf("missing" to missing),
)

/** Raised when Jira answers an HTTP error — mapped to 502 `JIRA_UNAVAILABLE`. */
class JiraUnavailableException(message: String, cause: Throwable? = null) :
    ForgePluginHttpException(502, "JIRA_UNAVAILABLE", message, null, cause)
