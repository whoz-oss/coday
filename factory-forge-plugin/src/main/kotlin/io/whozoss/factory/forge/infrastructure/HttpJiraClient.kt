package io.whozoss.factory.forge.infrastructure

import io.whozoss.factory.forge.domain.JiraComment
import io.whozoss.factory.forge.domain.JiraDomain
import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.forge.port.JiraTicketFetch
import io.whozoss.factory.forge.port.JiraUnavailableException
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.client.RestClient
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * HTTP adapter for the Jira REST API v3.
 *
 * Port of `factory/src/adapters/jira/jira-client.ts`. Missing credentials raise
 * [io.whozoss.factory.forge.port.JiraNotConfiguredException] (501) with the exact
 * Node message; any HTTP error raises [JiraUnavailableException] (502).
 */
class HttpJiraClient(
    builder: RestClient.Builder,
    private val baseUrl: String?,
    private val email: String?,
    private val apiToken: String?,
) : JiraClient {

    private val client: RestClient = builder.build()

    override fun isConfigured(): Boolean =
        !baseUrl.isNullOrBlank() && !email.isNullOrBlank() && !apiToken.isNullOrBlank()

    override fun missingCredentials(): List<String> = buildList {
        if (baseUrl.isNullOrBlank()) add("JIRA_BASE_URL")
        if (email.isNullOrBlank()) add("JIRA_EMAIL")
        if (apiToken.isNullOrBlank()) add("JIRA_API_TOKEN")
    }

    private fun authorizationHeader(): String {
        val credentials = Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray(Charsets.UTF_8))
        return "Basic $credentials"
    }

    private fun base(): String = baseUrl!!.replace(Regex("/+$"), "")

    private fun fetchComments(ticketId: String): List<JiraComment> {
        val pageSize = 50
        val all = mutableListOf<JiraComment>()
        var startAt = 0
        while (true) {
            val uri = "${base()}/rest/api/3/issue/$ticketId/comment" +
                "?orderBy=-created&maxResults=$pageSize&startAt=$startAt"
            val page = try {
                client.get().uri(uri).header("Authorization", authorizationHeader())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
            } catch (error: Exception) {
                throw JiraUnavailableException("Jira comments API failure pour $ticketId", error)
            }
            val comments = (page?.get("comments") as? List<*>) ?: emptyList<Any?>()
            val total = (page?.get("total") as? Number)?.toInt() ?: 0
            for (comment in comments) {
                val record = comment as? Map<*, *> ?: continue
                val author = record["author"] as? Map<*, *>
                val authorName = author?.get("displayName") as? String
                    ?: author?.get("emailAddress") as? String
                    ?: author?.get("accountId") as? String
                    ?: "Unknown"
                val created = record["created"] as? String ?: ""
                val body = record["body"]?.let { raw ->
                    if (raw is String) raw else JiraDomain.extractAdfText(raw).trim()
                } ?: ""
                all.add(JiraComment(authorName, created, body))
            }
            startAt += comments.size
            if (comments.isEmpty() || startAt >= total) break
        }
        return all
    }

    override fun fetchTicket(ticketId: String): JiraTicketFetch {
        val uri = "${base()}/rest/api/3/issue/$ticketId"
        val data = try {
            client.get().uri(uri).header("Authorization", authorizationHeader())
                .header("Accept", "application/json")
                .retrieve()
                .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
        } catch (error: Exception) {
            throw JiraUnavailableException("Jira API failure pour $ticketId", error)
        }
        val fields = (data?.get("fields") as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        val summary = fields["summary"] as? String ?: ""

        val parent = fields["parent"] as? Map<*, *>
        val epicKey = parent?.get("key") as? String
        val epicSummary = (parent?.get("fields") as? Map<*, *>)?.get("summary") as? String

        var description = ""
        fields["description"]?.let { raw ->
            description = if (raw is String) raw else JiraDomain.extractAdfText(raw).trim()
        }

        var acceptanceCriteria = ""
        for ((key, value) in fields) {
            if (value == null) continue
            val stringKey = key.toString()
            if (stringKey.lowercase().contains("acceptance") || stringKey == "customfield_10016") {
                if (value is String) {
                    acceptanceCriteria = value
                    break
                } else if (value is Map<*, *>) {
                    acceptanceCriteria = JiraDomain.extractAdfText(value).trim()
                    break
                }
            }
        }

        val allComments = fetchComments(ticketId)
        val (included, omitted) = JiraDomain.applyCommentBudget(allComments, JiraDomain.COMMENTS_CHAR_BUDGET)

        val sections = mutableListOf("## Summary\n$summary")
        if (description.isNotEmpty()) sections.add("## Description\n$description")
        if (acceptanceCriteria.isNotEmpty()) sections.add("## Acceptance criteria\n$acceptanceCriteria")
        if (included.isNotEmpty()) sections.add("## Comments\n${formatCommentsSection(included, omitted)}")

        val fieldCount = listOf(summary, description, acceptanceCriteria).count { it.isNotEmpty() }

        return JiraTicketFetch(
            ticketContent = sections.joinToString("\n\n"),
            summary = summary,
            epicKey = epicKey,
            epicSummary = epicSummary,
            fieldCount = fieldCount,
            commentCount = allComments.size,
            commentsIncluded = included.size,
            commentsTruncated = omitted > 0,
        )
    }

    private fun formatCommentsSection(comments: List<JiraComment>, omitted: Int): String {
        val parts = comments.map { comment ->
            val date = if (comment.created.isNotEmpty()) formatDate(comment.created) else ""
            "**${comment.author}** ($date):\n${comment.body}"
        }
        var section = parts.joinToString("\n\n---\n\n")
        if (omitted > 0) {
            section += "\n\n*($omitted older comment${if (omitted == 1) "" else "s"} omitted — budget exceeded)*"
        }
        return section
    }

    private fun formatDate(created: String): String = try {
        OffsetDateTime.parse(created).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: Exception) {
        created.take(10)
    }
}
