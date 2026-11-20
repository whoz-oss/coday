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
import reactor.core.publisher.Mono
import java.net.URI

/**
 * One-hour prompt caching for Claude models reached through the Requesty router (OpenAI-compatible API).
 *
 * Requesty caches Claude prompts automatically with the provider's default 5-minute TTL. A longer TTL needs
 * explicit `cache_control` markers, which Spring AI cannot express on OpenAI messages: [interceptor] (blocking
 * calls, RestClient) and [filter] (streaming calls, WebClient) rewrite the outgoing `/chat/completions` JSON
 * body with [withOneHourCacheControl] instead.
 *
 * [ChatModelFactory] only installs the hooks when the user chose [PromptCacheTtl.ONE_HOUR] and the base URL
 * [isRequesty]. Otherwise nothing is installed and the request stays the one Spring AI builds.
 *
 * Every rewrite failure leaves the body as it was: the call is never failed for caching.
 */
internal object RequestyPromptCache : KLogging() {
    /** Anthropic rejects a request carrying more cache breakpoints than this. */
    const val MAX_BREAKPOINTS = 4

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
                ?.let { runCatching { URI(it.trim()).host }.getOrNull() }
                ?.lowercase()
                ?: return false
        return host == REQUESTY_DOMAIN || host.endsWith(".$REQUESTY_DOMAIN")
    }

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
     * an inserter that renders the original one into [RewritingClientHttpRequest], which buffers the encoded
     * bytes, rewrites them and only then writes them, with a matching `Content-Length`, to the real request.
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
     * [body] with one-hour cache markers when [path] is a chat completions endpoint and the body is a JSON
     * object [withOneHourCacheControl] understands; the very same array otherwise.
     */
    fun rewriteChatCompletionsBody(
        path: String?,
        body: ByteArray,
    ): ByteArray {
        if (!isChatCompletions(path)) return body
        return try {
            val tree = mapper.readTree(body) as? ObjectNode
            if (tree == null) {
                logger.debug { "Requesty 1h cache: body of $path is not a JSON object, sent unchanged" }
                return body
            }
            val rewritten = withOneHourCacheControl(tree)
            if (rewritten === tree) body else mapper.writeValueAsBytes(rewritten)
        } catch (e: Exception) {
            logger.debug(e) { "Requesty 1h cache: could not rewrite the body of $path, sent unchanged" }
            body
        }
    }

    /**
     * A copy of the OpenAI chat completions [body] asking Requesty for a one-hour cache:
     * - `requesty.auto_cache` is set to false (other `requesty` fields are kept), so the router's automatic
     *   5-minute breakpoint cannot clash with ours (Anthropic rejects a different TTL on the same block);
     * - the last system message and the last message with text get a one-hour `cache_control` on their last
     *   text part, a string content being turned into a single text part first.
     *
     * Messages without text (an assistant message only carrying `tool_calls`) are never touched, no more than
     * [MAX_BREAKPOINTS] markers end up in the body, and applying it again changes nothing.
     *
     * Returns [body] itself, untouched, when it does not look like a chat completions body or already carries
     * markers this function did not set.
     */
    fun withOneHourCacheControl(body: ObjectNode): ObjectNode {
        val requesty = body.get("requesty")
        val understood =
            body.get("messages") is ArrayNode &&
                (requesty == null || requesty.isNull || requesty.isObject) &&
                body.cacheControls().all { it == oneHourCacheControl() }
        if (!understood) {
            logger.debug { "Requesty 1h cache: unexpected chat completions body, sent unchanged" }
            return body
        }

        val result = body.deepCopy()
        val requestyOptions = result.get("requesty") as? ObjectNode ?: result.putObject("requesty")
        requestyOptions.put("auto_cache", false)

        val messages = (result.get("messages") as ArrayNode).filterIsInstance<ObjectNode>()
        val lastSystem = messages.lastOrNull { it.get("role")?.asText() == "system" && it.hasText() }
        val lastWithText = messages.lastOrNull { it.hasText() }
        // By identity: two distinct messages may have equal JSON.
        val targets = listOfNotNull(lastSystem, lastWithText.takeIf { it !== lastSystem })
        var breakpoints = result.cacheControls().count()
        targets.forEach { message ->
            if (message.markLastTextPart(canAdd = breakpoints < MAX_BREAKPOINTS)) breakpoints++
        }
        return result
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

    /**
     * Puts the one-hour marker on the last text part of this message. Returns true when that adds a breakpoint,
     * which only happens when [canAdd]; re-marking an already marked part adds none.
     */
    private fun ObjectNode.markLastTextPart(canAdd: Boolean): Boolean {
        val content = get("content")
        return when {
            content != null && content.isTextual && content.asText().isNotEmpty() -> {
                if (!canAdd) return false
                val part = nodes.objectNode().put("type", "text").put("text", content.asText())
                part.set<JsonNode>("cache_control", oneHourCacheControl())
                set<JsonNode>("content", nodes.arrayNode().add(part))
                true
            }

            content is ArrayNode -> {
                val part = content.lastOrNull { it.isTextPart() } as? ObjectNode ?: return false
                val added = !part.has("cache_control")
                if (added && !canAdd) return false
                part.set<JsonNode>("cache_control", oneHourCacheControl())
                added
            }

            else -> {
                false
            }
        }
    }

    /**
     * Buffers what the original body inserter writes, rewrites it with [rewriteChatCompletionsBody], fixes
     * `Content-Length` (the JDK connector sizes the body from it) and writes the result to [delegate].
     */
    private class RewritingClientHttpRequest(
        delegate: ClientHttpRequest,
        private val path: String,
    ) : ClientHttpRequestDecorator(delegate) {
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
            DataBufferUtils
                .join(body)
                .map { buffer ->
                    try {
                        ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                    } finally {
                        DataBufferUtils.release(buffer)
                    }
                }.defaultIfEmpty(ByteArray(0))
                .flatMap { bytes ->
                    val rewritten = rewriteChatCompletionsBody(path, bytes)
                    delegate.headers.contentLength = rewritten.size.toLong()
                    delegate.writeWith(Mono.just(delegate.bufferFactory().wrap(rewritten)))
                }
    }
}
