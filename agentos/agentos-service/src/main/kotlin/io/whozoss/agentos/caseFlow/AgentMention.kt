package io.whozoss.agentos.caseFlow

/**
 * Parsing of the leading `@agentName` mention that routes a user message to an agent.
 *
 * Shared by agent selection ([CaseServiceImpl]) and by agents that parse the raw message
 * text themselves (e.g. [io.whozoss.agentos.agent.AgentLoop]), so both sides always agree
 * on what a mention is.
 */
object AgentMention {
    /**
     * Matches an `@mention` at the start of a trimmed message, e.g. `@my-agent`.
     *
     * Agent names may contain letters, digits, hyphens and underscores only.
     * Using `\S+` was too broad: a message like `@inspector https://...` would
     * capture the entire `inspector https://...` string when the separator is a
     * non-breaking space (U+00A0) or any other non-ASCII whitespace character,
     * because `\S` in Java/Kotlin regex only excludes ASCII whitespace by default.
     * The tighter character class `[\w-]+` stops at the first space-like or
     * special character, ensuring only the agent name token is captured.
     */
    private val LEADING_MENTION_REGEX = """^@([\w-]+)""".toRegex()

    /** Returns the agent name mentioned at the start of [text], or null when there is none. */
    fun extractName(text: String): String? = LEADING_MENTION_REGEX.find(text.trim())?.groupValues?.get(1)

    /** Returns [text] without its leading mention, trimmed. Returns the trimmed text unchanged when there is none. */
    fun strip(text: String): String = text.trim().replaceFirst(LEADING_MENTION_REGEX, "").trim()
}
