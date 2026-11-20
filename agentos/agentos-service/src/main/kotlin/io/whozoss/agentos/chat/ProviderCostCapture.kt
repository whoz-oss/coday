package io.whozoss.agentos.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KLogging
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import reactor.core.publisher.Mono
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * The cost a provider reported for the request in flight, e.g. Requesty's `usage.cost`.
 *
 * Spring AI parses OpenAI-compatible responses into a fixed `Usage` record and silently drops
 * unknown fields, so the reported cost must be read from the raw HTTP response by
 * [ProviderCostCapture] before parsing. [UsageTrackingChatModel] clears it before each request
 * and takes it when accounting that request; one instance is bound to one agent run, whose
 * requests are sequential.
 */
class ProviderReportedCost {
    private val pending = AtomicReference<Double?>(null)

    fun report(cost: Double) = pending.set(cost)

    fun take(): Double? = pending.getAndSet(null)

    fun clear() = pending.set(null)
}

/** HTTP hooks reading `usage.cost` from OpenAI-compatible responses, without altering them. */
object ProviderCostCapture : KLogging() {
    private val mapper = jacksonObjectMapper()
    private const val COST_FIELD = "\"cost\""
    private const val SSE_DATA_PREFIX = "data:"

    /** Blocking path (`call()`): buffers the JSON body, reads the cost, and replays the body. */
    fun interceptor(sink: ProviderReportedCost): ClientHttpRequestInterceptor =
        ClientHttpRequestInterceptor { request, body, execution ->
            val response = execution.execute(request, body)
            val isJson = response.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) == true
            if (!response.statusCode.is2xxSuccessful || !isJson) return@ClientHttpRequestInterceptor response
            val bytes = response.body.use { it.readAllBytes() }
            extractCost(bytes)?.let(sink::report)
            BufferedResponse(response, bytes)
        }

    /** Streaming path (`stream()`): scans SSE `data:` lines, including lines split across buffers. */
    fun filter(sink: ProviderReportedCost): ExchangeFilterFunction =
        ExchangeFilterFunction.ofResponseProcessor { response ->
            if (!response.statusCode().is2xxSuccessful) return@ofResponseProcessor Mono.just(response)
            val scanner = SseCostScanner(sink)
            Mono.just(
                response.mutate().body { body -> body.doOnNext(scanner::accept) }.build(),
            )
        }

    internal fun extractCost(json: ByteArray): Double? =
        runCatching {
            mapper
                .readTree(json)
                .path("usage")
                .path("cost")
                .takeIf { it.isNumber }
                ?.doubleValue()
                ?.takeIf { it.isFinite() && it >= 0 }
        }.getOrElse {
            logger.debug { "Provider response is not readable JSON; reported cost ignored" }
            null
        }

    /** Splits the byte stream on newlines so multi-byte characters and JSON never break mid-line. */
    internal class SseCostScanner(
        private val sink: ProviderReportedCost,
    ) {
        private val pending = ByteArrayOutputStream()

        fun accept(buffer: DataBuffer) {
            // Absolute reads leave the read position untouched for Spring AI's decoder.
            val start = buffer.readPosition()
            accept(ByteArray(buffer.readableByteCount()) { buffer.getByte(start + it) })
        }

        @Synchronized
        fun accept(bytes: ByteArray) {
            var start = 0
            bytes.forEachIndexed { index, byte ->
                if (byte == '\n'.code.toByte()) {
                    pending.write(bytes, start, index - start)
                    line(pending.toByteArray())
                    pending.reset()
                    start = index + 1
                }
            }
            pending.write(bytes, start, bytes.size - start)
        }

        private fun line(bytes: ByteArray) {
            val text = bytes.toString(Charsets.UTF_8).trim()
            if (!text.startsWith(SSE_DATA_PREFIX) || !text.contains(COST_FIELD)) return
            extractCost(text.removePrefix(SSE_DATA_PREFIX).trim().toByteArray())?.let(sink::report)
        }
    }

    private class BufferedResponse(
        private val delegate: ClientHttpResponse,
        private val bytes: ByteArray,
    ) : ClientHttpResponse {
        override fun getStatusCode(): HttpStatusCode = delegate.statusCode

        override fun getStatusText(): String = delegate.statusText

        override fun getHeaders(): HttpHeaders = delegate.headers

        override fun getBody(): InputStream = ByteArrayInputStream(bytes)

        override fun close() = delegate.close()
    }
}
