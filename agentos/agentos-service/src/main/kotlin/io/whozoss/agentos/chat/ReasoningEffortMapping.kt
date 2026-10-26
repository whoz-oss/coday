package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.openai.OpenAiChatOptions

/*
 * Translation of [ReasoningEffort] into each provider's native setting, applied by
 * [ChatModelFactory] on the default options of the chat model. A null effort always keeps the
 * behaviour the factory had before the setting existed.
 *
 * Anthropic has no entry: Spring AI 1.1.2 cannot send `output_config.effort`, and
 * [io.whozoss.agentos.aiModel.AiModelServiceImpl] rejects an effort on Anthropic providers.
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
 * some of them also read `reasoning_effort`. Thinking stays off for a null or [ReasoningEffort.NONE]
 * effort, as before. When it is on, the server must split the reasoning out of the content
 * (reasoning parser), otherwise it ends up in the agent's answer.
 */
internal fun OpenAiChatOptions.Builder.applyVllmReasoningEffort(effort: ReasoningEffort?): OpenAiChatOptions.Builder {
    val level = effort?.takeIf { it != ReasoningEffort.NONE }
    level?.let { reasoningEffort(it.wireValue()) }
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

/**
 * Spring AI exposes two Gemini thinking levels, LOW and HIGH. [ReasoningEffort.NONE] sets a zero
 * thinking budget, which only the models that can stop thinking accept.
 */
internal fun GoogleGenAiChatOptions.Builder.applyReasoningEffort(effort: ReasoningEffort?): GoogleGenAiChatOptions.Builder =
    when (effort) {
        null -> this
        ReasoningEffort.NONE -> thinkingBudget(0)
        ReasoningEffort.LOW -> thinkingLevel(GoogleGenAiThinkingLevel.LOW)
        ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX ->
            thinkingLevel(GoogleGenAiThinkingLevel.HIGH)
    }

private fun ReasoningEffort.wireValue(): String = name.lowercase()
