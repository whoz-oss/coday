package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.http.ResponseEntity
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real Spring AI clients and native tool execution; only the provider transport is mocked. */
class ChatClientUsageSwitchSpec : StringSpec({
    listOf(false, true).forEach { enabled ->
        listOf(false, true).forEach { streaming ->
            listOf(false, true).forEach { withTool ->
                "usage $enabled supports ${if (streaming) "stream" else "call"} with tool=$withTool" {
                    val requests = AtomicInteger()
                    val toolsExecuted = AtomicInteger()
                    val gateChecks = AtomicInteger()
                    val callback = object : ToolCallback {
                        override fun getToolDefinition(): ToolDefinition = ToolDefinition.builder()
                            .name("lookup")
                            .description("Return a test result")
                            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                            .build()

                        override fun call(input: String): String {
                            toolsExecuted.incrementAndGet()
                            return "tool result"
                        }
                    }
                    val nativeUsage = OpenAiApi.Usage(5, 10, 15, null, null)
                    fun nextMessage(): Pair<OpenAiApi.ChatCompletionFinishReason, OpenAiApi.ChatCompletionMessage> {
                        val toolRound = requests.getAndIncrement() == 0 && withTool
                        val toolCalls = if (toolRound) {
                            listOf(
                                OpenAiApi.ChatCompletionMessage.ToolCall(
                                    "call-1",
                                    "function",
                                    OpenAiApi.ChatCompletionMessage.ChatCompletionFunction("lookup", "{}"),
                                ),
                            )
                        } else {
                            emptyList()
                        }
                        return Pair(
                            if (toolRound) OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS else OpenAiApi.ChatCompletionFinishReason.STOP,
                            OpenAiApi.ChatCompletionMessage(
                                if (toolRound) null else "Done",
                                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                                null,
                                null,
                                toolCalls,
                                null,
                                null,
                                null,
                                null,
                            ),
                        )
                    }
                    val api = mockk<OpenAiApi>()
                    every { api.chatCompletionEntity(any(), any()) } answers {
                        val (reason, message) = nextMessage()
                        ResponseEntity.ok(
                            OpenAiApi.ChatCompletion(
                                "test-response",
                                listOf(OpenAiApi.ChatCompletion.Choice(reason, 0, message, null)),
                                0L,
                                "test-model",
                                null,
                                null,
                                "chat.completion",
                                nativeUsage,
                            ),
                        )
                    }
                    every { api.chatCompletionStream(any(), any()) } answers {
                        val (reason, message) = nextMessage()
                        Flux.just(
                            OpenAiApi.ChatCompletionChunk(
                                "test-response",
                                listOf(OpenAiApi.ChatCompletionChunk.ChunkChoice(reason, 0, message, null)),
                                0L,
                                "test-model",
                                null,
                                null,
                                "chat.completion.chunk",
                                nativeUsage,
                            ),
                        )
                    }
                    val nativeModel = OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model("test-model").build())
                        .build()
                    val factory = mockk<ChatModelFactory> {
                        every { createChatModel(any(), any(), any(), any(), any(), any(), any()) } returns nativeModel
                    }
                    val provider = AiProvider(
                        namespaceId = UUID.randomUUID(),
                        name = "test-provider",
                        apiType = AiApiType.OpenAI,
                    )
                    val model = AiModel(
                        aiProviderId = provider.id,
                        apiModelName = "test-model",
                        pricing = ModelPricing(inputMTokens = 1_000_000.0, outputMTokens = 1_000_000.0),
                    )
                    // Supplying an accumulator while OFF must still bypass accounting and its gate.
                    val accumulator = UsageAccumulator().also {
                        it.beforeCall = {
                            check(enabled) { "Accounting gate was invoked while usage is disabled" }
                            gateChecks.incrementAndGet()
                            CompletableFuture.completedFuture(null)
                        }
                    }
                    val client = ChatClientProvider(factory, usageConfig = UsageConfigProperties(enabled))
                        .getChatClient(model, provider, accumulator = accumulator)
                    // AgentSimple attaches resolved tool callbacks to each prompt, not to model defaults.
                    val prompt = client.prompt("Hello")
                    if (withTool) prompt.toolCallbacks(callback)
                    val content = if (streaming) {
                        prompt.stream().content().collectList().block(Duration.ofSeconds(3))!!.joinToString("")
                    } else {
                        prompt.call().content()
                    }

                    content shouldBe "Done"
                    requests.get() shouldBe if (withTool) 2 else 1
                    toolsExecuted.get() shouldBe if (withTool) 1 else 0
                    accumulator.hasData shouldBe enabled
                    accumulator.snapshot().calls shouldBe if (enabled) requests.get().toLong() else 0L
                    accumulator.total.totalTokens shouldBe if (enabled) requests.get() * 15L else 0L
                    gateChecks.get() shouldBe if (enabled) (if (withTool) 3 else 1) else 0
                }
            }
        }
    }
})
