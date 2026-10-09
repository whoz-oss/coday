package io.whozoss.factory.forge.domain

/** One flattened Jira comment. */
data class JiraComment(val author: String, val created: String, val body: String)

/**
 * Pure Jira domain: ticket-id extraction, Atlassian Document Format flattening
 * and the comment character budget.
 *
 * Port of `factory/src/domain/forge-bmad/jira.ts`.
 */
object JiraDomain {

    /** Budget of characters for the comments included in ticketContent. */
    const val COMMENTS_CHAR_BUDGET = 8000

    private val BLOCK_TYPES = setOf(
        "paragraph",
        "heading",
        "listItem",
        "bulletList",
        "orderedList",
        "blockquote",
        "codeBlock",
        "rule",
    )

    private val URL_TICKET = Regex("/browse/([A-Z][A-Z0-9]+-\\d+)", RegexOption.IGNORE_CASE)
    private val ID_TICKET = Regex("^([A-Z][A-Z0-9]+-\\d+)$", RegexOption.IGNORE_CASE)

    /**
     * Extract the Jira identifier from a raw id or a full URL.
     *
     * `'PROJ-1234'` → `'PROJ-1234'`; `'…/browse/PROJ-1234'` → `'PROJ-1234'`;
     * `'proj-1234'` → `'PROJ-1234'`; anything else → null.
     */
    fun extractTicketId(input: Any?): String? {
        if (input !is String || input.isEmpty()) return null
        URL_TICKET.find(input)?.let { return it.groupValues[1].uppercase() }
        ID_TICKET.find(input)?.let { return it.groupValues[1].uppercase() }
        return null
    }

    /**
     * Recursively extract the raw text of an Atlassian Document Format (ADF)
     * node, appending a newline after structural block types.
     */
    fun extractAdfText(node: Any?): String {
        if (node !is Map<*, *>) return ""
        if (node["type"] == "text" && node["text"] is String) return node["text"] as String
        val children = node["content"] as? List<*> ?: emptyList<Any?>()
        val parts = children.joinToString("") { extractAdfText(it) }
        return if (node["type"] in BLOCK_TYPES) "$parts\n" else parts
    }

    /** Apply the character budget to comments (ordered newest-first). */
    fun applyCommentBudget(comments: List<JiraComment>, budget: Int): Pair<List<JiraComment>, Int> {
        var remaining = budget
        val included = mutableListOf<JiraComment>()
        for (comment in comments) {
            val size = comment.author.length + comment.created.length + comment.body.length + 50
            if (remaining <= 0) break
            included.add(comment)
            remaining -= size
        }
        return included to (comments.size - included.size)
    }
}
