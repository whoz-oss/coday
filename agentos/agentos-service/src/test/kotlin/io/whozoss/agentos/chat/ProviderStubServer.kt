package io.whozoss.agentos.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal stub of the Anthropic (`/v1/messages`) and OpenAI-compatible (`/v1/chat/completions`) APIs: records
 * the raw body and the headers of each POST per path and answers a valid completion, as plain JSON or, when
 * [streaming] is set, as a server-sent event stream.
 *
 * Shared by the wire specs ([ProviderOptionsWireSpec], [RequestyPromptCacheWireSpec]).
 */
internal class ProviderStubServer {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val bodies = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val headers = ConcurrentHashMap<String, CopyOnWriteArrayList<Map<String, String>>>()

    @Volatile
    var streaming = false

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext(ANTHROPIC_PATH) { exchange -> handle(exchange, ANTHROPIC_JSON, ANTHROPIC_SSE) }
        server.createContext(OPENAI_PATH) { exchange -> handle(exchange, OPENAI_JSON, OPENAI_SSE) }
    }

    fun start() = server.start()

    fun stop() = server.stop(0)

    fun reset() {
        bodies.clear()
        headers.clear()
        streaming = false
    }

    fun requestBodies(path: String): List<String> = bodies[path].orEmpty()

    /** The headers of each POST received on [path], names lowercased, first value only. */
    fun requestHeaders(path: String): List<Map<String, String>> = headers[path].orEmpty()

    private fun handle(
        exchange: HttpExchange,
        json: String,
        sse: String,
    ) {
        exchange.use {
            headers.computeIfAbsent(it.requestURI.path) { CopyOnWriteArrayList() } +=
                it.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }
            bodies.computeIfAbsent(it.requestURI.path) { CopyOnWriteArrayList() } += String(it.requestBody.readBytes(), Charsets.UTF_8)
            val (contentType, body) = if (streaming) "text/event-stream" to sse else "application/json" to json
            val bytes = body.toByteArray(Charsets.UTF_8)
            it.responseHeaders.add("Content-Type", contentType)
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { out -> out.write(bytes) }
        }
    }

    companion object {
        const val ANTHROPIC_PATH = "/v1/messages"
        const val OPENAI_PATH = "/v1/chat/completions"

        private const val ANTHROPIC_JSON =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[{"type":"text","text":"Done"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":2,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

        private val ANTHROPIC_SSE =
            listOf(
                "message_start" to
                    """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}}""",
                "content_block_start" to
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }

        private const val OPENAI_JSON =
            """{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-test","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}"""

        private val OPENAI_SSE =
            listOf(
                """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-test","choices":[{"index":0,"delta":{"role":"assistant","content":"Done"},"finish_reason":null}]}""",
                """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-test","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
                "[DONE]",
            ).joinToString("") { "data: $it\n\n" }
    }
}
