package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.netty.buffer.UnpooledByteBufAllocator
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBuffer
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.springframework.mock.http.client.MockClientHttpRequest as MockBlockingRequest
import org.springframework.mock.http.client.reactive.MockClientHttpRequest as MockReactiveRequest

/**
 * The two HTTP hooks of [RequestyPromptCache], driven directly: what they hand to the next step for a given
 * request, without a server. The end-to-end behaviour is covered by [RequestyPromptCacheWireSpec].
 */
class RequestyPromptCacheHooksUnitSpec :
    StringSpec({
        val mapper = ObjectMapper()
        val chatUrl = "https://router.eu.requesty.ai/v1/chat/completions"
        val chatBody = """{"messages":[{"role":"system","content":"System"},{"role":"user","content":"Hello"}]}""".toByteArray()

        fun ByteArray.isMarked(): Boolean = mapper.readTree(this).get("requesty")?.get("auto_cache")?.asBoolean() == false

        // ---------------------------------------------------------------------------------------------
        // RestClient interceptor
        // ---------------------------------------------------------------------------------------------

        /** Runs the interceptor and returns the request and the body it passed on. */
        fun intercept(
            url: String,
            body: ByteArray,
        ): Pair<MockBlockingRequest, ByteArray> {
            val request = MockBlockingRequest(HttpMethod.POST, URI(url)).apply { headers.contentLength = body.size.toLong() }
            var sent: ByteArray? = null
            RequestyPromptCache.interceptor().intercept(
                request,
                body,
                ClientHttpRequestExecution { _, bytes ->
                    sent = bytes
                    MockClientHttpResponse(ByteArray(0), HttpStatus.OK)
                },
            )
            return request to sent!!
        }

        "the interceptor rewrites a chat completions body and fixes Content-Length" {
            val (request, sent) = intercept(chatUrl, chatBody)

            sent.isMarked() shouldBe true
            request.headers.contentLength shouldBe sent.size.toLong()
        }

        "the interceptor passes another endpoint's body on as is" {
            val (request, sent) = intercept("https://router.eu.requesty.ai/v1/embeddings", chatBody)

            sent shouldBeSameInstanceAs chatBody
            request.headers.contentLength shouldBe chatBody.size.toLong()
        }

        "the interceptor passes a body above the size cap on as is" {
            val huge = RequestyPromptCacheTestBodies.chatBody(RequestyPromptCache.MAX_REWRITE_BYTES.toInt() + 1)

            intercept(chatUrl, huge).second shouldBeSameInstanceAs huge
        }

        // ---------------------------------------------------------------------------------------------
        // WebClient filter
        // ---------------------------------------------------------------------------------------------

        val context =
            object : BodyInserter.Context {
                override fun messageWriters(): List<HttpMessageWriter<*>> = ExchangeStrategies.withDefaults().messageWriters()

                override fun serverRequest(): Optional<ServerHttpRequest> = Optional.empty()

                override fun hints(): Map<String, Any> = emptyMap()
            }

        fun buffersOf(
            bytes: ByteArray,
            parts: Int,
            factory: DataBufferFactory = DefaultDataBufferFactory.sharedInstance,
        ): List<DataBuffer> {
            val size = (bytes.size + parts - 1) / parts
            return (bytes.indices step size).map { start -> factory.wrap(bytes.copyOfRange(start, minOf(start + size, bytes.size))) }
        }

        /** Body written in [parts] buffers with writeWith, Content-Length set like Spring's encoder does. */
        fun multiBufferInserter(
            bytes: ByteArray,
            parts: Int,
        ) = BodyInserter<Any, ClientHttpRequest> { message, _ ->
            message.headers.contentLength = bytes.size.toLong()
            message.writeWith(Flux.fromIterable(buffersOf(bytes, parts)))
        }

        /** Body written in [parts] flushed chunks with writeAndFlushWith. */
        fun flushingInserter(
            bytes: ByteArray,
            parts: Int,
        ) = BodyInserter<Any, ClientHttpRequest> { message, _ ->
            message.writeAndFlushWith(Flux.fromIterable(buffersOf(bytes, parts)).map { Mono.just(it) })
        }

        /** Runs the filter on [request] and returns the request it passed on. */
        fun passOn(request: ClientRequest): ClientRequest {
            var passed: ClientRequest? = null
            RequestyPromptCache
                .filter()
                .filter(
                    request,
                    ExchangeFunction { next ->
                        passed = next
                        Mono.empty<ClientResponse>()
                    },
                ).block()
            return passed!!
        }

        /** Runs the filter, then the body inserter of the request it passed on; returns both. */
        fun filter(
            url: String,
            inserter: BodyInserter<Any, ClientHttpRequest>,
        ): Triple<ClientRequest, ClientRequest, MockReactiveRequest> {
            val request = ClientRequest.create(HttpMethod.POST, URI(url)).body(inserter).build()
            val passed = passOn(request)
            val output = MockReactiveRequest(HttpMethod.POST, URI(url))
            passed.body().insert(output, context).block()
            return Triple(request, passed, output)
        }

        /** The request the filter passes on for a chat completions body written by [inserter]. */
        fun passOnChat(inserter: BodyInserter<Any, ClientHttpRequest>): ClientRequest =
            passOn(ClientRequest.create(HttpMethod.POST, URI(chatUrl)).body(inserter).build())

        /** Buffers whose release can be observed: reference-counted Netty buffers. */
        val countedBuffers = NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT)

        fun DataBuffer.refCount(): Int = (this as NettyDataBuffer).nativeBuffer.refCnt()

        fun MockReactiveRequest.writtenBytes(): ByteArray {
            val out = ByteArrayOutputStream()
            body.collectList().block()!!.forEach { buffer ->
                out.write(ByteArray(buffer.readableByteCount()).also { buffer.read(it) })
            }
            return out.toByteArray()
        }

        "the filter rewrites a chat completions body written in several buffers and fixes Content-Length" {
            val (_, _, output) = filter(chatUrl, multiBufferInserter(chatBody, parts = 3))

            val written = output.writtenBytes()
            written.isMarked() shouldBe true
            output.headers.contentLength shouldBe written.size.toLong()
        }

        "the filter rewrites a body written with writeAndFlushWith" {
            val (_, _, output) = filter(chatUrl, flushingInserter(chatBody, parts = 4))

            val written = output.writtenBytes()
            written.isMarked() shouldBe true
            output.headers.contentLength shouldBe written.size.toLong()
        }

        "the filter passes a body above the size cap on byte for byte" {
            val huge = RequestyPromptCacheTestBodies.chatBody(RequestyPromptCache.MAX_REWRITE_BYTES.toInt() + 1)

            val (_, _, output) = filter(chatUrl, multiBufferInserter(huge, parts = 2))

            output.writtenBytes() shouldBe huge
            output.headers.contentLength shouldBe huge.size.toLong()
        }

        "the filter passes a request to another endpoint on as is" {
            val (request, passed, output) =
                filter("https://router.eu.requesty.ai/v1/embeddings", multiBufferInserter(chatBody, parts = 2))

            passed shouldBeSameInstanceAs request
            output.writtenBytes() shouldBe chatBody
        }

        "the filter starts passing a body through as soon as it crosses the size cap, keeping its Content-Length" {
            val source = Sinks.many().unicast().onBackpressureBuffer<DataBuffer>()
            val inserter =
                BodyInserter<Any, ClientHttpRequest> { message, _ ->
                    message.headers.contentLength = 12_345_678
                    message.writeWith(source.asFlux())
                }
            val output = MockReactiveRequest(HttpMethod.POST, URI(chatUrl))
            val written = CopyOnWriteArrayList<DataBuffer>()
            output.setWriteHandler { body -> body.doOnNext { written += it }.then() }
            val quarter = ByteArray((RequestyPromptCache.MAX_REWRITE_BYTES / 4 + 1).toInt()) { 'a'.code.toByte() }

            val done = passOnChat(inserter).body().insert(output, context).toFuture()
            repeat(4) { source.tryEmitNext(DefaultDataBufferFactory.sharedInstance.wrap(quarter)) }

            // The fourth buffer crosses the cap: the four are written before the body ends.
            written.size shouldBe 4
            done.isDone shouldBe false

            source.tryEmitNext(DefaultDataBufferFactory.sharedInstance.wrap("tail".toByteArray()))
            source.tryEmitComplete()
            done.get(5, TimeUnit.SECONDS)

            written.size shouldBe 5
            written.sumOf { it.readableByteCount().toLong() } shouldBe quarter.size * 4L + 4
            output.headers.contentLength shouldBe 12_345_678
        }

        "the filter releases the buffers it collected after rewriting them" {
            val held = buffersOf(chatBody, parts = 3, factory = countedBuffers)
            val (_, _, output) = filter(chatUrl, BodyInserter<Any, ClientHttpRequest> { message, _ -> message.writeWith(Flux.fromIterable(held)) })

            output.writtenBytes().isMarked() shouldBe true
            held.forEach { it.refCount() shouldBe 0 }
        }

        "a write cancelled mid-body releases the buffers collected so far" {
            val held = buffersOf(chatBody, parts = 2, factory = countedBuffers)
            val inserter =
                BodyInserter<Any, ClientHttpRequest> { message, _ ->
                    message.writeWith(Flux.concat(Flux.fromIterable(held), Flux.never()))
                }
            val output = MockReactiveRequest(HttpMethod.POST, URI(chatUrl))

            val subscription = passOnChat(inserter).body().insert(output, context).subscribe()
            held.forEach { it.refCount() shouldBe 1 }
            subscription.dispose()

            held.forEach { it.refCount() shouldBe 0 }
        }

        "a body that fails mid-way releases the buffers collected so far and propagates the error" {
            val held = buffersOf(chatBody, parts = 2, factory = countedBuffers)
            val inserter =
                BodyInserter<Any, ClientHttpRequest> { message, _ ->
                    message.writeWith(Flux.concat(Flux.fromIterable(held), Flux.error(IllegalStateException("boom"))))
                }
            val output = MockReactiveRequest(HttpMethod.POST, URI(chatUrl))

            shouldThrow<IllegalStateException> { passOnChat(inserter).body().insert(output, context).block() }.message shouldBe "boom"
            held.forEach { it.refCount() shouldBe 0 }
        }
    })
