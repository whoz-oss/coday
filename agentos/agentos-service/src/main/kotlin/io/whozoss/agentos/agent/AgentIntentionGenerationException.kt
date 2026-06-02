package io.whozoss.agentos.agent

internal sealed class AgentIntentionGenerationException(message: String) : Exception(message) {
    /** The raw LLM response that triggered this failure, null when the LLM produced nothing. */
    abstract val response: String?

    /** The response was null/empty or lacked the required XML tags. */
    class InvalidFormat(message: String, override val response: String? = null) :
        AgentIntentionGenerationException(message)

    /** The XML was well-formed but referenced a tool that does not exist. */
    class UnknownTool(val toolName: String, override val response: String) :
        AgentIntentionGenerationException("Unknown tool '$toolName'")
}
