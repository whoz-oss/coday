package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatOptions

/*
 * Translation of [ReasoningEffort] into each provider's native setting, applied by
 * [ChatModelFactory] on the default options of the chat model. A null effort always keeps the
 * behaviour the factory had before the setting existed.
 *
 * Anthropic and Gemini have no entry: they do not have
 * [io.whozoss.agentos.sdk.aiProvider.AiApiType.supportsReasoningEffort], and
 * [io.whozoss.agentos.aiModel.AiModelServiceImpl] rejects an effort on their models.
 */

/**
 * OpenAI-compatible APIs (OpenAI, routers such as Requesty) take `reasoning_effort` as a string:
 * the level is sent verbatim, lowercase. The provider rejects a level the model does not support,
 * so a wrong configuration shows up on the first call.
 */
internal fun OpenAiChatOptions.Builder.applyReasoningEffort(effort: ReasoningEffort?): OpenAiChatOptions.Builder =
    effort?.let { reasoningEffort(it.wireValue()) } ?: this

/**
 * vLLM serves models whose chat template switches thinking on and off (`enable_thinking`), and
 * some of them also read `reasoning_effort`, which vLLM limits to low, medium and high: XHIGH and
 * MAX are sent as high. Thinking stays off for a null or [ReasoningEffort.NONE] effort, as before.
 * When it is on, the server must split the reasoning out of the content (reasoning parser),
 * otherwise it ends up in the agent's answer.
 */
internal fun OpenAiChatOptions.Builder.applyVllmReasoningEffort(effort: ReasoningEffort?): OpenAiChatOptions.Builder {
    val level =
        when (effort) {
            null, ReasoningEffort.NONE -> null
            ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH -> effort.wireValue()
            ReasoningEffort.XHIGH, ReasoningEffort.MAX -> ReasoningEffort.HIGH.wireValue()
        }
    level?.let { reasoningEffort(it) }
    return extraBody(mapOf("chat_template_kwargs" to mapOf("enable_thinking" to (level != null))))
}

/** Ollama knows three levels; thinking stays off for a null or [ReasoningEffort.NONE] effort, as before. */
internal fun OllamaChatOptions.Builder.applyReasoningEffort(effort: ReasoningEffort?): OllamaChatOptions.Builder =
    when (effort) {
        null, ReasoningEffort.NONE -> disableThinking()
        ReasoningEffort.LOW -> thinkLow()
        ReasoningEffort.MEDIUM -> thinkMedium()
        ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX -> thinkHigh()
    }

private fun ReasoningEffort.wireValue(): String = name.lowercase()
