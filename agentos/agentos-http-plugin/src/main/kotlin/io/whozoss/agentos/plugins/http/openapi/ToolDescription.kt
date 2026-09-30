package io.whozoss.agentos.plugins.http.openapi

/**
 * Builds the tool description shown to the LLM for an operation:
 *
 * `[WRITE] ` prefix for non-GET operations, then `<METHOD> <path> — <summary>. <description>` with markdown
 * collapsed to single spaces and that part capped at [MAX_TEXT_LENGTH] characters, then
 * ` Returns: <2xx description>` when present, then ` Returns only: a, b` when `keepPaths` is configured.
 * An override text replaces the summary/description part.
 */
object ToolDescription {

    fun of(operation: OpenApiOperation, overrideText: String?, keepPaths: List<String>): String =
        buildString {
            if (operation.method != HttpMethod.GET) append(WRITE_PREFIX)
            append(operation.method.name).append(' ').append(operation.path)
            summaryPart(operation, overrideText)?.let { append(" — ").append(it) }
            operation.successDescription?.let { append(" Returns: ").append(collapse(it)) }
            if (keepPaths.isNotEmpty()) append(" Returns only: ").append(keepPaths.joinToString(", "))
        }

    private fun summaryPart(operation: OpenApiOperation, overrideText: String?): String? {
        val text = overrideText?.let { collapse(it) } ?: specText(operation)
        return text?.takeIf { it.isNotEmpty() }?.let(::truncate)
    }

    private fun specText(operation: OpenApiOperation): String? {
        val summary = operation.summary?.let { collapse(it) }?.takeIf { it.isNotEmpty() }?.let { sentence(it) }
        val description = operation.description?.let { collapse(it) }?.takeIf { it.isNotEmpty() }
        return listOfNotNull(summary, description).joinToString(" ").takeIf { it.isNotEmpty() }
    }

    private fun sentence(text: String): String = if (text.last() in SENTENCE_END) text else "$text."

    private fun truncate(text: String): String =
        if (text.length <= MAX_TEXT_LENGTH) text else text.take(MAX_TEXT_LENGTH - ELLIPSIS.length) + ELLIPSIS

    /** Collapses whitespace runs (including newlines) and strips common markdown markers. */
    private fun collapse(text: String): String =
        MARKDOWN_MARKERS.replace(text, "").let { WHITESPACE.replace(it, " ") }.trim()

    const val MAX_TEXT_LENGTH = 800
    private const val WRITE_PREFIX = "[WRITE] "
    private const val ELLIPSIS = "..."
    private val SENTENCE_END = setOf('.', '!', '?', ':')
    private val WHITESPACE = Regex("\\s+")
    private val MARKDOWN_MARKERS = Regex("[`*]|^#{1,6}\\s+", RegexOption.MULTILINE)
}
