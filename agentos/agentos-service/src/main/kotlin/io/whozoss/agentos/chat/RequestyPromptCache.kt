package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import mu.KLogging
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.http.client.reactive.ClientHttpRequestDecorator
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI

/**
 * One-hour prompt caching for Claude models via Requesty (OpenAI-compatible API).
 *
 * Requesty caches Claude prompts automatically with the provider's default 5-minute TTL. A longer TTL needs
 * explicit `cache_control` markers, which Spring AI cannot express on OpenAI messages: [interceptor] (blocking
 * calls, RestClient) and [filter] (streaming calls, WebClient) rewrite the outgoing `/chat/completions` JSON
 * body with [addOneHourCacheControl] instead.
 *
 * [ChatModelFactory] only installs the hooks when the user chose [PromptCacheTtl.ONE_HOUR], the base URL
 * [isRequesty] and the model [isClaudeModel]. Otherwise nothing is installed and the request stays the one
 * Spring AI builds.
 *
 * Every rewrite failure leaves the body as it was: the call is never failed for caching. Bodies larger than
 * [MAX_REWRITE_BYTES] are sent as they are.
 */
internal object RequestyPromptCache : KLogging() {
    /** Above this size a request body is sent unchanged: neither copied, parsed nor rewritten. */
    const val MAX_REWRITE_BYTES = 8L * 1024 * 1024

    private const val REQUESTY_DOMAIN = "requesty.ai"
    private const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    /** Exact numbers, so a re-serialized body keeps the values Spring AI wrote (e.g. temperature). */
    private val mapper: ObjectMapper =
        ObjectMapper()
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    private val nodes = JsonNodeFactory.instance

    /** Whether [baseUrl] points at the Requesty router (`requesty.ai` or one of its subdomains). */
    fun isRequesty(baseUrl: String?): Boolean {
        val host =
            baseUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { URI(it.trim()).host }.getOrNull() }
                ?.trimEnd('.')
                ?.lowercase()
                ?: return false
        return host == REQUESTY_DOMAIN || host.endsWith(".$REQUESTY_DOMAIN")
    }

    /** Whether [modelName] is a Claude model, e.g. Requesty's `anthropic/claude-*` or `policy/claude-*`. */
    fun isClaudeModel(modelName: String?): Boolean = modelName?.contains("claude", ignoreCase = true) == true

    /** RestClient hook (blocking calls): rewrites the body of `/chat/completions` requests. */
    fun interceptor(): ClientHttpRequestInterceptor =
        ClientHttpRequestInterceptor { request, body, execution ->
            val rewritten = rewriteChatCompletionsBody(request.uri.path, body)
            if (rewritten !== body && request.headers.contentLength >= 0) {
                request.headers.contentLength = rewritten.size.toLong()
            }
            execution.execute(request, rewritten)
        }

    /**
     * WebClient hook (streaming calls): the request body is a `BodyInserter`, not bytes. The filter wraps it in
     * an inserter that renders the original one into [RewritingClientHttpRequest], which collects the encoded
     * buffers, rewrites them and only then writes them, with a matching `Content-Length`, to the real request.
     */
    fun filter(): ExchangeFilterFunction =
        ExchangeFilterFunction { request, next ->
            val path = request.url().path
            if (!isChatCompletions(path)) {
                next.exchange(request)
            } else {
                val original = request.body()
                val rewritingInserter =
                    BodyInserter<Any, ClientHttpRequest> { outputMessage, context ->
                        original.insert(RewritingClientHttpRequest(outputMessage, path), context)
                    }
                next.exchange(ClientRequest.from(request).body(rewritingInserter).build())
            }
        }

    /**
     * [body] with one-hour cache markers when [path] is a chat completions endpoint, the body is at most
     * [MAX_REWRITE_BYTES] and [addOneHourCacheControl] placed a marker; the very same array otherwise.
     */
    fun rewriteChatCompletionsBody(
        path: String?,
        body: ByteArray,
    ): ByteArray {
        if (!isChatCompletions(path)) return body
        if (body.size > MAX_REWRITE_BYTES) {
            logger.debug { "Requesty 1h cache: body of $path is ${body.size} bytes, above $MAX_REWRITE_BYTES, sent unchanged" }
            return body
        }
        return try {
            val tree = mapper.readTree(body) as? ObjectNode
            when {
                tree == null -> {
                    logger.debug { "Requesty 1h cache: body of $path is not a JSON object, sent unchanged" }
                    body
                }

                addOneHourCacheControl(tree) -> {
                    mapper.writeValueAsBytes(tree)
                }

                else -> {
                    body
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Requesty 1h cache: could not rewrite the body of $path, sent unchanged" }
            body
        }
    }

    /**
     * Asks Requesty for a one-hour cache by editing the OpenAI chat completions [body] in place:
     * - the last system message and the last message with text get a one-hour `cache_control` on their last
     *   text part, a string content being turned into a single text part first;
     * - when at least one marker was placed, `requesty.auto_cache` is set to false (other `requesty` fields are
     *   kept), so the router's automatic 5-minute breakpoint cannot clash with ours (Anthropic rejects a
     *   different TTL on the same block, and a longer TTL after a shorter one).
     *
     * Messages without text (an assistant message only carrying `tool_calls`) are never touched. At most two
     * markers are placed, so Anthropic's limit of four breakpoints cannot be reached.
     *
     * A body whose messages already carry a `cache_control` (set by a caller, or by a previous pass) is left
     * as it is: mixing our markers with ones of unknown TTL could break the TTL ordering or the breakpoint
     * limit. This also makes a second pass a no-op. Only `messages` is scanned, so a tool parameter named
     * `cache_control` does not count.
     *
     * Returns true when the body was changed; false, with the body untouched, otherwise.
     */
    fun addOneHourCacheControl(body: ObjectNode): Boolean {
        val messages = body.get("messages")
        val requesty = body.get("requesty")
        if (messages !is ArrayNode || (requesty != null && !requesty.isNull && !requesty.isObject)) {
            logger.debug { "Requesty 1h cache: unexpected chat completions body, sent unchanged" }
            return false
        }
        if (messages.cacheControls().any()) {
            logger.debug { "Requesty 1h cache: messages already carry cache_control markers, sent unchanged" }
            return false
        }

        val objects = messages.filterIsInstance<ObjectNode>()
        val lastSystem = objects.lastOrNull { it.get("role")?.asText() == "system" && it.hasText() }
        val lastWithText = objects.lastOrNull { it.hasText() }
        // By identity: two distinct messages may have equal JSON.
        val placed = listOfNotNull(lastSystem, lastWithText.takeIf { it !== lastSystem }).count { it.markLastTextPart() }
        if (placed == 0) {
            logger.debug { "Requesty 1h cache: no message with text, sent unchanged" }
            return false
        }
        (body.get("requesty") as? ObjectNode ?: body.putObject("requesty")).put("auto_cache", false)
        return true
    }

    private fun isChatCompletions(path: String?): Boolean = path?.endsWith(CHAT_COMPLETIONS_PATH) == true

    private fun oneHourCacheControl(): ObjectNode = nodes.objectNode().put("type", "ephemeral").put("ttl", "1h")

    /** Every `cache_control` value anywhere in this node. */
    private fun JsonNode.cacheControls(): Sequence<JsonNode> =
        sequence {
            if (this@cacheControls is ObjectNode) get("cache_control")?.let { yield(it) }
            elements().forEach { yieldAll(it.cacheControls()) }
        }

    private fun JsonNode.isTextPart(): Boolean {
        val text = get("text")
        return this is ObjectNode && get("type")?.asText() == "text" && text != null && text.isTextual && text.asText().isNotEmpty()
    }

    private fun ObjectNode.hasText(): Boolean {
        val content = get("content") ?: return false
        return when {
            content.isTextual -> content.asText().isNotEmpty()
            content is ArrayNode -> content.any { it.isTextPart() }
            else -> false
        }
    }

    /** Puts the one-hour marker on the last text part of this message; false when it has none. */
    private fun ObjectNode.markLastTextPart(): Boolean {
        val content = get("content")
        val part =
            when {
                content != null && content.isTextual && content.asText().isNotEmpty() -> {
                    nodes.objectNode().put("type", "text").put("text", content.asText()).also {
                        set<JsonNode>("content", nodes.arrayNode().add(it))
                    }
                }

                content is ArrayNode -> {
                    content.lastOrNull { it.isTextPart() } as? ObjectNode
                }

                else -> {
                    null
                }
            } ?: return false
        part.set<JsonNode>("cache_control", oneHourCacheControl())
        return true
    }

    /**
     * Collects what the original body inserter writes, rewrites it with [rewriteChatCompletionsBody], fixes
     * `Content-Length` (the JDK connector sizes the body from it) and writes the result to [delegate].
     *
     * The encoder has already produced the whole body in memory: collecting only keeps references to its
     * buffers. Above [MAX_REWRITE_BYTES] they are written as they are, without being copied or parsed.
     */
    private class RewritingClientHttpRequest(
        delegate: ClientHttpRequest,
        private val path: String,
    ) : ClientHttpRequestDecorator(delegate) {
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
            Flux
                .from(body)
                .doOnDiscard(DataBuffer::class.java) { DataBufferUtils.release(it) }
                .collectList()
                .flatMap { buffers ->
                    val size = buffers.sumOf { it.readableByteCount().toLong() }
                    if (size > MAX_REWRITE_BYTES) {
                        logger.debug { "Requesty 1h cache: body of $path is $size bytes, above $MAX_REWRITE_BYTES, sent unchanged" }
                        delegate.writeWith(Flux.fromIterable(buffers))
                    } else {
                        val bytes = buffers.drainTo(size.toInt())
                        val rewritten = rewriteChatCompletionsBody(path, bytes)
                        if (rewritten !== bytes) delegate.headers.contentLength = rewritten.size.toLong()
                        delegate.writeWith(Mono.just(delegate.bufferFactory().wrap(rewritten)))
                    }
                }

        /** Flushing boundaries do not matter for a JSON request body: it goes through the same rewrite. */
        override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
            writeWith(Flux.from(body).concatMap { Flux.from<DataBuffer>(it) })

        /** Copies the buffers into one array and releases them. */
        private fun List<DataBuffer>.drainTo(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            try {
                forEach { buffer ->
                    val count = buffer.readableByteCount()
                    buffer.read(bytes, offset, count)
                    offset += count
                }
            } finally {
                forEach { DataBufferUtils.release(it) }
            }
            return bytes
        }
    }
}
