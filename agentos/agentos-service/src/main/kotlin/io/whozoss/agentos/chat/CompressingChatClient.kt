package io.whozoss.agentos.chat

import io.whozoss.agentos.util.IdCompressorService
import io.whozoss.agentos.util.MessageCompressorBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt

/**
 * A transparent decorator around [ChatClient] that compresses UUIDs/ObjectIds in outgoing
 * messages and decompresses them in LLM responses.
 *
 * This keeps all compression logic out of the business code (agents, context builders,
 * message converters). From the caller's perspective it behaves like a regular chat client:
 * pass uncompressed messages, receive uncompressed text back.
 *
 * Two call patterns are supported, matching the ones used by [io.whozoss.agentos.agent.AgentAdvanced] and
 * [io.whozoss.agentos.agent.AgentIntentionGenerator]:
 * - [call]: synchronous single-response call.
 * - [stream]: streaming call that feeds/flushes each chunk through the decompressor.
 *
 * Note: [ChatClient] is retained as a separate field on [io.whozoss.agentos.agent.AgentAdvancedContext] for the
 * [io.whozoss.agentos.agent.ConfirmationManager], whose meta-calls (analyse/formulate) do not contain IDs and
 * therefore must NOT go through this wrapper (they use the raw [ChatClient] directly).
 */
class CompressingChatClient(
    private val delegate: ChatClient,
    private val compressorService: IdCompressorService,
) {
    /**
     * Compresses [messages], calls the delegate synchronously, and decompresses the response.
     *
     * @return the decompressed response text, or null when the LLM returned nothing.
     */
    fun call(messages: List<Message>): String? {
        val buffer = compressorService.newBuffer()
        val compressed = messages.map { it.compress(buffer) }
        val raw = delegate.prompt(Prompt(compressed)).call().content()?.trim() ?: return null
        return compressorService.uncompress(raw, buffer)
    }

    /**
     * Compresses [messages], streams the delegate response, and feeds each chunk through
     * the streaming decompressor. Callers receive already-decompressed [ChatResponse] objects
     * whose text has been patched with the decompressed content.
     *
     * The returned [Flow] emits:
     * - one item per non-empty decompressed chunk while the stream is active;
     * - one final item carrying the flushed carry (if non-empty) after the stream ends.
     *
     * The original [ChatResponse] metadata (finish reason, etc.) is preserved from the
     * last upstream chunk so callers can still inspect it.
     */
    fun stream(messages: List<Message>): Flow<ChatResponse> {
        val buffer = compressorService.newBuffer()
        val compressed = messages.map { it.compress(buffer) }
        return flow {
            var lastResponse: ChatResponse? = null
            delegate.prompt(Prompt(compressed))
                .stream()
                .chatResponse()
                .asFlow()
                .collect { response ->
                    lastResponse = response
                    val chunk = response.result.output.text?.takeIf { it.isNotEmpty() } ?: return@collect
                    val decompressed = compressorService.feed(chunk, buffer)
                    if (decompressed.isNotEmpty()) {
                        emit(response.withText(decompressed))
                    }
                }
            // Flush any remaining carry after the stream ends.
            val flushed = compressorService.flush(buffer)
            if (flushed.isNotEmpty()) {
                val carrier = lastResponse ?: return@flow
                emit(carrier.withText(flushed))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Compresses all text content in this [Message] using [buffer].
     *
     * Handles four message types:
     * - [UserMessage] / [SystemMessage]: plain text, compressed directly.
     * - [AssistantMessage] with tool calls: each tool call's arguments JSON is
     *   compressed individually; plain-text assistant messages are compressed as usual.
     * - [ToolResponseMessage]: each tool response body is compressed individually.
     */
    private fun Message.compress(buffer: MessageCompressorBuffer): Message {
        return when (this) {
            is UserMessage -> UserMessage(compressorService.compress(this.text, buffer))
            is SystemMessage -> SystemMessage(compressorService.compress(this.text, buffer))
            is AssistantMessage -> {
                val toolCalls = this.toolCalls
                if (toolCalls.isEmpty()) {
                    // Plain assistant text message
                    AssistantMessage(compressorService.compress(this.text ?: return this, buffer))
                } else {
                    // Tool-call message: compress each call's arguments JSON
                    val compressedCalls = toolCalls.map { call ->
                        AssistantMessage.ToolCall(
                            call.id(),
                            call.type(),
                            call.name(),
                            compressorService.compress(call.arguments(), buffer),
                        )
                    }
                    AssistantMessage.builder().toolCalls(compressedCalls).build()
                }
            }
            is ToolResponseMessage -> {
                // Compress each tool response body
                val compressedResponses = this.responses.map { r ->
                    ToolResponseMessage.ToolResponse(
                        r.id(),
                        r.name(),
                        compressorService.compress(r.responseData(), buffer),
                    )
                }
                ToolResponseMessage.builder().responses(compressedResponses).build()
            }
            else -> this
        }
    }

    /**
     * Returns a copy of this [ChatResponse] with the first result's text replaced by [text].
     * Metadata (finish reason, usage, etc.) is preserved from the original.
     */
    private fun ChatResponse.withText(text: String): ChatResponse {
        val patched = AssistantMessage(text)
        return ChatResponse(
            listOf(
                Generation(
                    patched,
                    this.result.metadata,
                )
            ),
            this.metadata,
        )
    }
}
