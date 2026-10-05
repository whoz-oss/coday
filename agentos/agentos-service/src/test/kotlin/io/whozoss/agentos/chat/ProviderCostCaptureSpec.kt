package io.whozoss.agentos.chat

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID

/** Real Spring AI OpenAI client against a local OpenAI-compatible server reporting `usage.cost`. */
class ProviderCostCaptureSpec :
    StringSpec({
        val reportedCost = 0.0421

        fun completion(cost: Double?) =
            """{"id":"r","object":"chat.completion","created":0,"model":"m",
               "choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],
               "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15${cost?.let { ",\"cost\":$it" } ?: ""}}}"""

        fun chunks(cost: Double?) =
            listOf(
                """{"id":"r","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"Do"},"finish_reason":null}]}""",
                """{"id":"r","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"content":"ne"},"finish_reason":"stop"}]}""",
                """{"id":"r","object":"chat.completion.chunk","created":0,"model":"m","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15${cost?.let { ",\"cost\":$it" } ?: ""}}}""",
            ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

        /** Serves one completion per request; streaming responses are written in small flushed slices. */
        fun server(cost: Double?): HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/chat/completions") { exchange ->
                    val request = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    val streaming = request.contains("\"stream\":true")
                    val body = (if (streaming) chunks(cost) else completion(cost)).toByteArray()
                    exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { out ->
                        body.toList().chunked(7).forEach {
                            out.write(it.toByteArray())
                            out.flush()
                        }
                    }
                }
                start()
            }

        fun client(
            server: HttpServer,
            accumulator: UsageAccumulator,
        ) = ChatClientProvider(
            ChatModelFactory(ObservationRegistry.NOOP, AnthropicProperties(true), UsageConfigProperties(true)),
            usageConfig = UsageConfigProperties(true),
        ).getChatClient(
            AiModel(
                aiProviderId = UUID.randomUUID(),
                apiModelName = "m",
                // Estimate would be 15.0: the reported cost must win when present.
                pricing = ModelPricing(inputMTokens = 1_000_000.0, outputMTokens = 1_000_000.0),
            ),
            AiProvider(
                name = "gateway",
                apiType = AiApiType.OpenAI,
                baseUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "test",
            ),
            accumulator = accumulator,
        )

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "stream" else "call"

            "$mode stores the provider-reported cost instead of the pricing estimate" {
                val server = server(reportedCost)
                try {
                    val accumulator = UsageAccumulator()
                    val prompt = client(server, accumulator).prompt("Hello")
                    val content =
                        if (streaming) {
                            prompt.stream().content().collectList().block(Duration.ofSeconds(5))!!.joinToString("")
                        } else {
                            prompt.call().content()
                        }
                    content shouldBe "Done"
                    accumulator.total.totalTokens shouldBe 15L
                    accumulator.total.estimatedCostUsd shouldBe reportedCost
                } finally {
                    server.stop(0)
                }
            }

            "$mode falls back to the pricing estimate when no cost is reported" {
                val server = server(null)
                try {
                    val accumulator = UsageAccumulator()
                    val prompt = client(server, accumulator).prompt("Hello")
                    if (streaming) prompt.stream().content().blockLast(Duration.ofSeconds(5)) else prompt.call().content()
                    accumulator.total.estimatedCostUsd shouldBe 15.0
                } finally {
                    server.stop(0)
                }
            }
        }

        "SSE scanner reads a cost split across buffers and multi-byte characters" {
            val sink = ProviderReportedCost()
            val scanner = ProviderCostCapture.SseCostScanner(sink)
            val bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"é\"}}]}\n\ndata: {\"usage\":{\"cost\":0.5}}\n\n".toByteArray()
            bytes.toList().chunked(3).forEach { scanner.accept(it.toByteArray()) }
            sink.take() shouldBe 0.5
            sink.take() shouldBe null
        }
    })
