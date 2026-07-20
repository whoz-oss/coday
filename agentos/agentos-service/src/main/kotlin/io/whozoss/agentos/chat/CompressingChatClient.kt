package io.whozoss.agentos.chat

import io.whozoss.agentos.util.IdCompressorService
import io.whozoss.agentos.util.MessageCompressorBuffer
import mu.KLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.client.ChatClient.StreamResponseSpec
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicReference

/**
 * A proper [ChatClient] decorator that compresses UUIDs/ObjectIds in outgoing
 * messages and decompresses them in LLM responses.
 *
 * This keeps all compression logic out of the business code (agents, context builders,
 * message converters). From the caller's perspective it behaves like a regular [ChatClient]:
 * pass uncompressed messages via the standard fluent API, receive uncompressed text back.
 *
 * Implements [ChatClient] via Kotlin `by` delegation: only the three [prompt] overrides
 * are intercepted to inject compression; all other [ChatClient] methods delegate
 * transparently to [delegate].
 *
 * Note: [ChatClient] is retained as a separate field on [io.whozoss.agentos.agent.AgentAdvancedContext]
 * for the [io.whozoss.agentos.agent.ConfirmationManager], whose meta-calls (analyse/formulate)
 * do not contain IDs and therefore must NOT go through this wrapper (they use the raw
 * [ChatClient] directly).
 */
class CompressingChatClient(
    private val delegate: ChatClient,
    private val compressorService: IdCompressorService,
) : ChatClient by delegate {

    companion object : KLogging()

    override fun prompt(): ChatClientRequestSpec {
        logger.debug { "[CompressingChatClient] prompt() — no-arg, compression deferred to message-level" }
        return CompressingRequestSpec(delegate.prompt(), compressorService.newBuffer())
    }

    override fun prompt(prompt: Prompt): ChatClientRequestSpec {
        val buffer = compressorService.newBuffer()
        val original = prompt.instructions
        val compressed = Prompt(original.map { it.compress(buffer) })
        logger.debug { "[CompressingChatClient] prompt(Prompt) — ${original.size} message(s), buffer=${buffer.hashCode()}" }
        return CompressingRequestSpec(delegate.prompt(compressed), buffer)
    }

    override fun prompt(content: String): ChatClientRequestSpec {
        val buffer = compressorService.newBuffer()
        val compressed = compressorService.compress(content, buffer)
        logger.debug { "[CompressingChatClient] prompt(String) — ${content.length} chars → ${compressed.length} chars compressed, buffer=${buffer.hashCode()}" }
        return CompressingRequestSpec(delegate.prompt(compressed), buffer)
    }

    // -------------------------------------------------------------------------
    // Inner wrapper classes
    // -------------------------------------------------------------------------

    /**
     * Wraps a [ChatClientRequestSpec], delegating all methods transparently except
     * [call] and [stream], which are intercepted to inject decompression wrappers.
     *
     * The [buffer] is the same one used to compress the outgoing prompt, so the
     * decompressor can resolve aliases back to the original IDs.
     */
    private inner class CompressingRequestSpec(
        private val delegate: ChatClientRequestSpec,
        private val buffer: MessageCompressorBuffer,
    ) : ChatClientRequestSpec by delegate {

        override fun call(): CallResponseSpec {
            logger.debug { "[CompressingChatClient] call() — buffer=${buffer.hashCode()}" }
            return CompressingCallSpec(delegate.call(), buffer)
        }

        override fun stream(): StreamResponseSpec {
            logger.debug { "[CompressingChatClient] stream() — buffer=${buffer.hashCode()}" }
            return CompressingStreamSpec(delegate.stream(), buffer)
        }
    }

    /**
     * Wraps a [CallResponseSpec], delegating all methods transparently except
     * [content] and [chatResponse], which decompress the LLM's text output.
     */
    private inner class CompressingCallSpec(
        private val delegate: CallResponseSpec,
        private val buffer: MessageCompressorBuffer,
    ) : CallResponseSpec by delegate {

        override fun content(): String? {
            val raw = delegate.content()?.trim() ?: run {
                logger.debug { "[CompressingChatClient] call.content() — null response from delegate" }
                return null
            }
            val decompressed = compressorService.uncompress(raw, buffer)
            logger.debug {
                "[CompressingChatClient] call.content() — ${raw.length} chars raw → ${decompressed.length} chars decompressed, buffer=${buffer.hashCode()}"
            }
            return decompressed
        }

        override fun chatResponse(): ChatResponse? {
            val response = delegate.chatResponse() ?: run {
                logger.debug { "[CompressingChatClient] call.chatResponse() — null response from delegate" }
                return null
            }
            // A metadata-only response (e.g. an Anthropic MESSAGE_START chunk with an empty
            // content list) has a null result; it carries no text, so pass it through untouched.
            val raw = response.result?.output?.text?.trim() ?: return response
            val decompressed = compressorService.uncompress(raw, buffer)
            logger.debug {
                "[CompressingChatClient] call.chatResponse() — ${raw.length} chars raw → ${decompressed.length} chars decompressed, buffer=${buffer.hashCode()}"
            }
            return response.withText(decompressed)
        }
    }

    /**
     * Wraps a [StreamResponseSpec], delegating all methods transparently except
     * [content] and [chatResponse], which feed/flush chunks through the streaming
     * decompressor.
     *
     * The returned [Flux] emits:
     * - one item per non-empty decompressed chunk while the stream is active;
     * - one final item carrying the flushed carry (if non-empty) after the stream ends.
     *
     * The original [ChatResponse] metadata (finish reason, etc.) is preserved from
     * the last upstream chunk so callers can still inspect it.
     */
    private inner class CompressingStreamSpec(
        private val delegate: StreamResponseSpec,
        private val buffer: MessageCompressorBuffer,
    ) : StreamResponseSpec by delegate {

        /**
         * Intercepts the upstream [Flux] to feed each chunk through the streaming
         * decompressor and flush any carry at the end.
         *
         * Uses an [AtomicReference] to track the last upstream [ChatResponse] so its
         * metadata can be reused for the flushed carry item. The reference is written
         * by [doOnNext] (upstream thread) and read by [concatWith] after the upstream
         * completes — the happens-before guarantee of Reactor's completion signal makes
         * this safe without additional synchronization.
         *
         * The original [ChatResponse] metadata (finish reason, usage, etc.) is preserved
         * from the last upstream chunk.
         */
        override fun chatResponse(): Flux<ChatResponse> {
            logger.debug { "[CompressingChatClient] stream.chatResponse() started — buffer=${buffer.hashCode()}" }
            val lastResponseRef = AtomicReference<ChatResponse?>(null)
            var totalRawChars = 0
            var totalDecompressedChars = 0
            return delegate.chatResponse()
                .doOnNext { lastResponseRef.set(it) }
                .flatMapIterable { response ->
                    val results = mutableListOf<ChatResponse>()
                    // Streaming metadata chunks (e.g. Anthropic MESSAGE_START, content=[]) have a
                    // null result and no text to compress, so skip them.
                    val chunk = response.result?.output?.text?.takeIf { it.isNotEmpty() }
                    if (chunk != null) {
                        totalRawChars += chunk.length
                        val decompressed = compressorService.feed(chunk, buffer)
                        if (decompressed.isNotEmpty()) {
                            totalDecompressedChars += decompressed.length
                            results.add(response.withText(decompressed))
                        }
                    }
                    results
                }
                .concatWith(
                    Flux.defer {
                        val flushed = compressorService.flush(buffer)
                        val carrier = lastResponseRef.get()
                        if (flushed.isNotEmpty()) totalDecompressedChars += flushed.length
                        logger.debug {
                            "[CompressingChatClient] stream.chatResponse() done — " +
                                "$totalRawChars raw chars in, $totalDecompressedChars decompressed chars out, " +
                                "flush=${flushed.length} chars, buffer=${buffer.hashCode()}"
                        }
                        if (flushed.isNotEmpty() && carrier != null) {
                            Flux.just(carrier.withText(flushed))
                        } else {
                            Flux.empty()
                        }
                    }
                )
        }

        override fun content(): Flux<String> {
            logger.debug { "[CompressingChatClient] stream.content() started — buffer=${buffer.hashCode()}" }
            return chatResponse().mapNotNull { it.result?.output?.text?.takeIf { t -> t.isNotEmpty() } }
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
