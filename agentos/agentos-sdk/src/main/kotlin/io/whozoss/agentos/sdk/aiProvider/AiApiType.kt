package io.whozoss.agentos.sdk.aiProvider

/**
 * @property supportsReasoningEffort Whether AgentOS can send a [ReasoningEffort] to this API.
 *   False for [Anthropic]: Spring AI 1.1.2 cannot send `output_config.effort`, and current
 *   Claude models reject a thinking budget.
 */
enum class AiApiType(
    val supportsReasoningEffort: Boolean = true,
) {
    OpenAI,
    Anthropic(supportsReasoningEffort = false),
    Gemini,
    vLLM,
    Ollama,
}
