package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.Prompt
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** One request sent to a provider, its messages exactly as sent, and the text of its response once it came. */
data class LlmExchange(
    val messages: List<Message>,
    /** Null until the whole response came (never set when the request failed or was cut short). */
    val responseText: String? = null,
    /** When the request was sent: when the provider's prompt cache saw its prefix. */
    val sentAt: Instant? = null,
    /**
     * The tokens a follow-up request re-sends, as the provider reported them: the prompt (input, cache read and cache
     * write tokens) and the answer (output tokens). Null until reported.
     */
    val prefixTokens: Long? = null,
)

/**
 * Keeps the last exchange of a chat model with its provider, tool rounds included. Fed by [UsageTrackingChatModel]
 * (see [ChatClientProvider.getChatClient] `exchangeRecorder`): an untracked model runs its tool rounds out of
 * sight, so it records nothing.
 */
class LlmExchangeRecorder(
    private val now: () -> Instant = Instant::now,
) {
    private val last = AtomicReference<LlmExchange?>(null)

    val lastExchange: LlmExchange? get() = last.get()

    fun requestSent(prompt: Prompt) = last.set(LlmExchange(prompt.instructions.toList(), sentAt = now()))

    fun responseReceived(text: String?) {
        last.updateAndGet { it?.copy(responseText = text.orEmpty()) }
    }

    /** A response without usage (0 prompt tokens) leaves the size unknown. */
    fun usageReceived(usage: LlmUsage) {
        val promptTokens = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
        if (promptTokens > 0) last.updateAndGet { it?.copy(prefixTokens = promptTokens + usage.outputTokens) }
    }
}

/**
 * What makes the requests built by [ChatClientProvider.getChatClient] for a model and a provider what they are,
 * besides their messages and tools. Two requests with equal options, messages and tools put the same bytes on the
 * wire, with the same credentials.
 *
 * Nothing secret or verbatim is kept: the API key only as a [credentialFingerprint], the headers (which may carry
 * credentials too) only as a [headersDigest], both SHA-256. The real values are resolved again when needed.
 */
data class LlmRequestOptions(
    val apiType: AiApiType,
    val baseUrl: String?,
    val apiModelName: String,
    val temperature: Double?,
    val maxCompletionTokens: Int?,
    val reasoningEffort: ReasoningEffort?,
    /** SHA-256 of the API key, null without one. */
    val credentialFingerprint: String?,
    /** SHA-256 of the headers, in name order. */
    val headersDigest: String,
) {
    companion object {
        fun of(
            model: AiModel,
            provider: AiProvider,
        ) = LlmRequestOptions(
            apiType = provider.apiType,
            baseUrl = provider.baseUrl,
            apiModelName = model.apiModelName,
            temperature = model.temperature,
            maxCompletionTokens = model.maxCompletionTokens,
            reasoningEffort = model.reasoningEffort,
            credentialFingerprint = provider.apiKey?.let(::sha256),
            headersDigest = sha256(provider.headers.toSortedMap().entries.joinToString("\n") { (name, value) -> "$name:$value" }),
        )

        private fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
