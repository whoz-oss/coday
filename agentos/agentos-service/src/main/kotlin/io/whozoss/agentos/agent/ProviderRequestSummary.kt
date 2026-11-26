package io.whozoss.agentos.agent

/**
 * Compact, safe description of the request that an AI provider rejected, shown in the error event of the case.
 *
 * It deliberately holds no content: the conversation, the system prompt and the tool schemas stay out of the
 * event. What is left is what helps to understand a refusal (an unknown model, an oversized history, an invalid
 * tool name, see #1317) without being able to leak anything the user could not already see.
 *
 * [messageCount] and [toolNames] are null when the catch site cannot reach them cleanly (e.g. the advanced
 * execution mode, which builds several prompts per run and does not send its tools as API tool definitions).
 */
data class ProviderRequestSummary(
    val llmProvider: String,
    val llmModel: String,
    val messageCount: Int? = null,
    val toolNames: List<String>? = null,
) {
    /** Single-line rendering, e.g. `provider=openai, model=gpt-4o, messages=3, tools=2 (a, b)`. */
    fun render(): String =
        buildList {
            add("provider=$llmProvider")
            add("model=$llmModel")
            messageCount?.let { add("messages=$it") }
            toolNames?.let { names ->
                val shown = names.take(MAX_TOOL_NAMES).joinToString(", ")
                val more = if (names.size > MAX_TOOL_NAMES) ", +${names.size - MAX_TOOL_NAMES} more" else ""
                add(if (names.isEmpty()) "tools=0" else "tools=${names.size} ($shown$more)")
            }
        }.joinToString(", ")

    companion object {
        /** Bounds the event size for an agent with a very large tool set. */
        internal const val MAX_TOOL_NAMES = 50
    }
}
