package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
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
 * The level [apiType] actually receives for this configured level: verbatim for OpenAI-compatible
 * APIs, clamped to high where the API stops there (vLLM, Ollama), null where no effort is sent.
 * The translations below and the effort recorded on agent messages both rely on it.
 */
internal fun ReasoningEffort.sentTo(apiType: AiApiType): ReasoningEffort? =
    when (apiType) {
        AiApiType.vLLM, AiApiType.Ollama ->
            if (this == ReasoningEffort.XHIGH || this == ReasoningEffort.MAX) ReasoningEffort.HIGH else this
        else -> takeIf { apiType.supportsReasoningEffort }
    }

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
    val level = effort?.sentTo(AiApiType.vLLM)?.takeIf { it != ReasoningEffort.NONE }
    level?.let { reasoningEffort(it.wireValue()) }
    return extraBody(mapOf("chat_template_kwargs" to mapOf("enable_thinking" to (level != null))))
}

/** Ollama knows three levels; thinking stays off for a null or [ReasoningEffort.NONE] effort, as before. */
internal fun OllamaChatOptions.Builder.applyReasoningEffort(effort: ReasoningEffort?): OllamaChatOptions.Builder =
    when (effort?.sentTo(AiApiType.Ollama)) {
        null, ReasoningEffort.NONE -> disableThinking()
        ReasoningEffort.LOW -> thinkLow()
        ReasoningEffort.MEDIUM -> thinkMedium()
        else -> thinkHigh()
    }

private fun ReasoningEffort.wireValue(): String = name.lowercase()
