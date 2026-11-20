package io.whozoss.agentos.chat

import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.Prompt
import java.util.concurrent.atomic.AtomicReference

/** One request sent to a provider, its messages exactly as sent, and the text of its response once it came. */
data class LlmExchange(
    val messages: List<Message>,
    /** Null until the whole response came (never set when the request failed or was cut short). */
    val responseText: String? = null,
)

/**
 * Keeps the last exchange of a chat model with its provider, tool rounds included. Fed by [UsageTrackingChatModel]
 * (see [ChatClientProvider.getChatClient] `exchangeRecorder`): an untracked model runs its tool rounds out of
 * sight, so it records nothing.
 */
class LlmExchangeRecorder {
    private val last = AtomicReference<LlmExchange?>(null)

    val lastExchange: LlmExchange? get() = last.get()

    fun requestSent(prompt: Prompt) = last.set(LlmExchange(prompt.instructions.toList()))

    fun responseReceived(text: String?) {
        last.updateAndGet { it?.copy(responseText = text.orEmpty()) }
    }
}

/**
 * What a request built by [ChatClientProvider.getChatClient] sends besides its messages and tools, its API key
 * aside: two requests with equal options, messages and tools put the same bytes on the wire.
 */
data class LlmRequestOptions(
    val apiType: AiApiType,
    val baseUrl: String?,
    val headers: Map<String, String>,
    val apiModelName: String,
    val temperature: Double?,
    val maxCompletionTokens: Int?,
    val reasoningEffort: ReasoningEffort?,
) {
    companion object {
        fun of(
            model: AiModel,
            provider: AiProvider,
        ) = LlmRequestOptions(
            apiType = provider.apiType,
            baseUrl = provider.baseUrl,
            headers = provider.headers,
            apiModelName = model.apiModelName,
            temperature = model.temperature,
            maxCompletionTokens = model.maxCompletionTokens,
            reasoningEffort = model.reasoningEffort,
        )
    }
}
