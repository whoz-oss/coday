package io.whozoss.agentos.chat

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.usage.LlmUsage
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class UsageTrackingChatModelSpec :
    StringSpec({
        val model =
            AiModel(
                aiProviderId = UUID.randomUUID(),
                apiModelName = "test",
                pricing = ModelPricing(inputMTokens = 1_000_000.0, outputMTokens = 1_000_000.0),
            )

        fun response(
            tool: Boolean = false,
            withUsage: Boolean = true,
        ): ChatResponse {
            val message =
                AssistantMessage
                    .builder()
                    .content(if (tool) "" else "Done")
                    .toolCalls(if (tool) listOf(AssistantMessage.ToolCall("call-1", "function", "testTool", "{}")) else emptyList())
                    .build()
            val metadata = ChatResponseMetadata.builder()
            if (withUsage) metadata.usage(DefaultUsage(10, 5, 15, OpenAiApi.Usage(5, 10, 15, null, null)))
            return ChatResponse(listOf(Generation(message)), metadata.build())
        }

        "real ChatClient content terminal counts a synchronous response" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.call(any<Prompt>()) } returns response()
            val accumulator = UsageAccumulator()
            val client = ChatClient.builder(UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model)).build()
            client.prompt("Hello").call().content() shouldBe "Done"
            accumulator.total.totalTokens shouldBe 15L
            accumulator.total.estimatedCostUsd shouldBe 15.0
        }

        "tool rounds each account native usage and wait before executing the tool" {
            val toolsExecuted = AtomicInteger()
            val requests = AtomicInteger()
            val callback =
                object : ToolCallback {
                    override fun getToolDefinition(): ToolDefinition =
                        ToolDefinition
                            .builder()
                            .name(
                                "testTool",
                            ).description("Test tool")
                            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                            .build()

                    override fun call(input: String): String {
                        toolsExecuted.incrementAndGet()
                        return "ok"
                    }
                }
            val raw = mockk<ChatModel>()
            val options = ToolCallingChatOptions.builder().toolCallbacks(callback).build()
            every { raw.defaultOptions } returns options
            every { raw.stream(any<Prompt>()) } answers {
                val prompt = firstArg<Prompt>()
                (prompt.options as ToolCallingChatOptions).internalToolExecutionEnabled shouldBe false
                Flux.just(response(tool = requests.getAndIncrement() == 0))
            }
            val accumulator = UsageAccumulator()
            val permission = CompletableFuture<Void>()
            accumulator.beforeCall =
                { if (accumulator.hasData && !permission.isDone) permission else CompletableFuture.completedFuture(null) }
            val tracked = UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model)
            val result = tracked.stream(Prompt(listOf(UserMessage("Hello")), options)).collectList().toFuture()
            eventually(2.seconds) { accumulator.total.totalTokens shouldBe 15L }
            toolsExecuted.get() shouldBe 0
            requests.get() shouldBe 1
            result.isDone shouldBe false
            permission.complete(null)
            result
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
                .last()
                .result.output.text shouldBe "Done"
            toolsExecuted.get() shouldBe 1
            requests.get() shouldBe 2
            accumulator.total.totalTokens shouldBe 30L
            accumulator.total.estimatedCostUsd shouldBe 30.0
        }

        "empty trailing chunk does not erase the native usage" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.just(response(), response(withUsage = false))
            val accumulator = UsageAccumulator()
            UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model).stream(Prompt("Hello")).blockLast(Duration.ofSeconds(2))
            accumulator.total.totalTokens shouldBe 15L
            accumulator.total.estimatedCostUsd shouldBe 15.0
        }

        "stream error retains received usage and marks the invocation failed" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns
                Flux.concat(Flux.just(response()), Flux.error(IllegalStateException("provider failure")))
            val accumulator = UsageAccumulator()
            shouldThrow<IllegalStateException> {
                UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model).stream(Prompt("Hello")).blockLast(Duration.ofSeconds(2))
            }
            accumulator.total.totalTokens shouldBe 15L
            accumulator.failed shouldBe true
        }

        "cancellation records the last usage exactly once" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.concat(Flux.just(response()), Flux.never())
            val accumulator = UsageAccumulator()
            UsageTrackingChatModel(
                raw,
                accumulator,
                AiApiType.OpenAI,
                model,
            ).stream(Prompt("Hello")).take(1).blockLast(Duration.ofSeconds(2))
            accumulator.total.totalTokens shouldBe 15L
            accumulator.snapshot().calls shouldBe 1L
        }

        "mixed priced and unknown calls retain separate analytical facts" {
            val accumulator = UsageAccumulator()
            accumulator.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
            accumulator.record(LlmUsage(totalTokens = 20, estimatedCostUsd = null))
            accumulator.total.estimatedCostUsd shouldBe null
            accumulator.recordGroups().map { it.estimatedCostUsd } shouldBe listOf(12.0, null)
            accumulator.recordGroups().sumOf { it.totalTokens } shouldBe 30L
        }

        "tool round limit retains usage and marks a failed invocation" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.just(response(tool = true))
            val accumulator = UsageAccumulator()
            val tracked = UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model, maxToolRounds = 0)
            shouldThrow<IllegalStateException> { tracked.stream(Prompt("Hello")).blockLast(Duration.ofSeconds(2)) }
            accumulator.failed shouldBe true
            accumulator.total.totalTokens shouldBe 15L
        }
    })
