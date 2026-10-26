package io.whozoss.agentos.chat

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.CostRunStopped
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
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
            text: String = "Done",
        ): ChatResponse {
            val message =
                AssistantMessage
                    .builder()
                    .content(if (tool) "" else text)
                    .toolCalls(if (tool) listOf(AssistantMessage.ToolCall("call-1", "function", "testTool", "{}")) else emptyList())
                    .build()
            val metadata = ChatResponseMetadata.builder()
            if (withUsage) metadata.usage(DefaultUsage(10, 5, 15, OpenAiApi.Usage(5, 10, 15, null, null)))
            return ChatResponse(listOf(Generation(message)), metadata.build())
        }

        fun toolCallback(action: () -> String): ToolCallback =
            object : ToolCallback {
                override fun getToolDefinition(): ToolDefinition =
                    ToolDefinition
                        .builder()
                        .name("testTool")
                        .description("Test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build()

                override fun call(input: String): String = action()
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
                toolCallback {
                    toolsExecuted.incrementAndGet()
                    "ok"
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

        listOf(false, true).forEach { streaming ->
            "unknown tool in ${if (streaming) "stream" else "call"} retains usage and marks the invocation failed" {
                val raw = mockk<ChatModel>()
                every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
                every { raw.call(any<Prompt>()) } returns response(tool = true)
                every { raw.stream(any<Prompt>()) } returns Flux.just(response(tool = true))
                val accumulator = UsageAccumulator()
                val tracked = UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model)

                shouldThrow<IllegalStateException> {
                    if (streaming) {
                        tracked.stream(Prompt("Hello")).blockLast(Duration.ofSeconds(2))
                    } else {
                        tracked.call(Prompt("Hello"))
                    }
                }

                accumulator.total.totalTokens shouldBe 15L
                accumulator.total.estimatedCostUsd shouldBe 15.0
                accumulator.snapshot().calls shouldBe 1L
                accumulator.failed shouldBe true
            }
        }

        "wrapped tool interruption retains synchronous usage without marking a failure" {
            val interruption = CompletionException(AgentInterrupt.AwaitAnswer("Continue?"))
            val options = ToolCallingChatOptions.builder().toolCallbacks(toolCallback { throw interruption }).build()
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns options
            every { raw.call(any<Prompt>()) } returns response(tool = true)
            val accumulator = UsageAccumulator()

            shouldThrow<CompletionException> {
                UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model).call(Prompt("Hello"))
            } shouldBe interruption

            accumulator.total.totalTokens shouldBe 15L
            accumulator.snapshot().calls shouldBe 1L
            accumulator.failed shouldBe false
        }

        "wrapped cost stop before a streaming tool retains usage without marking a failure" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.just(response(tool = true))
            val accumulator = UsageAccumulator()
            accumulator.beforeCall = {
                if (accumulator.hasData) {
                    CompletableFuture.failedFuture(CompletionException(CostRunStopped()))
                } else {
                    CompletableFuture.completedFuture(null)
                }
            }

            shouldThrow<CostRunStopped> {
                UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model).stream(Prompt("Hello")).blockLast(Duration.ofSeconds(2))
            }

            accumulator.total.totalTokens shouldBe 15L
            accumulator.snapshot().calls shouldBe 1L
            accumulator.failed shouldBe false
        }

        "wrapped stream cancellation retains received usage without marking a failure" {
            val cancellation = CompletionException(CancellationException("cancelled"))
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.concat(Flux.just(response()), Flux.error(cancellation))
            val accumulator = UsageAccumulator()

            shouldThrow<CompletionException> {
                UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model).stream(Prompt("Hello")).blockLast(Duration.ofSeconds(2))
            } shouldBe cancellation

            accumulator.total.totalTokens shouldBe 15L
            accumulator.snapshot().calls shouldBe 1L
            accumulator.failed shouldBe false
        }

        "recoverable tool error returned to the model does not fail the invocation" {
            val toolsExecuted = AtomicInteger()
            val callback =
                toolCallback {
                    toolsExecuted.incrementAndGet()
                    "Error executing tool: boom"
                }
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().toolCallbacks(callback).build()
            val requests = AtomicInteger()
            every { raw.stream(any<Prompt>()) } answers { Flux.just(response(tool = requests.getAndIncrement() == 0)) }
            val accumulator = UsageAccumulator()

            UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model)
                .stream(Prompt("Hello"))
                .blockLast(Duration.ofSeconds(2))
                ?.result
                ?.output
                ?.text shouldBe "Done"

            toolsExecuted.get() shouldBe 1
            requests.get() shouldBe 2
            accumulator.total.totalTokens shouldBe 30L
            accumulator.snapshot().calls shouldBe 2L
            accumulator.failed shouldBe false
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

        listOf(false, true).forEach { streaming ->
            val mode = if (streaming) "stream" else "call"

            /** A model that always wants a tool; [summary] answers the request made at the limit, if any. */
            fun loopingRun(summary: ChatResponse?): LoopingRun {
                val toolsExecuted = AtomicInteger()
                val options = ToolCallingChatOptions.builder().toolCallbacks(toolCallback { "ok".also { toolsExecuted.incrementAndGet() } }).build()
                val prompts = CopyOnWriteArrayList<Prompt>()
                fun answer(prompt: Prompt): ChatResponse {
                    prompts.add(prompt)
                    val messages = prompt.instructions
                    val atLimit = messages.last() is UserMessage && messages.getOrNull(messages.size - 2) is ToolResponseMessage
                    return if (atLimit && summary != null) summary else response(tool = true)
                }
                val raw = mockk<ChatModel>()
                every { raw.defaultOptions } returns options
                every { raw.call(any<Prompt>()) } answers { answer(firstArg()) }
                every { raw.stream(any<Prompt>()) } answers { Flux.just(answer(firstArg())) }
                val accumulator = UsageAccumulator()
                val tracked = UsageTrackingChatModel(raw, accumulator, AiApiType.OpenAI, model, maxToolRounds = 2)
                val prompt = Prompt(listOf(UserMessage("Hello")), options)
                val responses =
                    if (streaming) {
                        tracked.stream(prompt).collectList().block(Duration.ofSeconds(3))!!
                    } else {
                        listOf(tracked.call(prompt))
                    }
                return LoopingRun(responses, prompts, toolsExecuted.get(), accumulator)
            }

            "$mode: at the tool round limit the model is asked to summarize, and the run completes" {
                val (responses, prompts, toolsExecuted, accumulator) = loopingRun(summary = response(text = "Summary of the work"))

                responses.joinToString("") { it.result?.output?.text.orEmpty() } shouldBe "Summary of the work"
                responses.last().metadata.get<Int>(UsageTrackingChatModel.MAX_ITERATIONS_REACHED) shouldBe 2
                toolsExecuted shouldBe 2
                // The summary request replaces the next round: no request is wasted.
                prompts.size shouldBe 3
                (prompts.last().instructions.last() as UserMessage).text shouldContain "Do not call any tool"
                accumulator.snapshot().calls shouldBe 3L
                accumulator.total.totalTokens shouldBe 45L
                accumulator.failed shouldBe false
            }

            "$mode: tool calls in the summary answer are not executed and a missing text gets a fallback" {
                val (responses, prompts, toolsExecuted, accumulator) = loopingRun(summary = null)

                responses.joinToString("") { it.result?.output?.text.orEmpty() } shouldContain
                    "maximum number of tool rounds (2)"
                responses.last().metadata.get<Int>(UsageTrackingChatModel.MAX_ITERATIONS_REACHED) shouldBe 2
                toolsExecuted shouldBe 2
                prompts.size shouldBe 3
                accumulator.failed shouldBe false
            }
        }

        "a run that answers before the limit carries no limit marker" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.just(response())
            val responses =
                UsageTrackingChatModel(raw, UsageAccumulator(), AiApiType.OpenAI, model, maxToolRounds = 2)
                    .stream(Prompt("Hello"))
                    .collectList()
                    .block(Duration.ofSeconds(2))!!
            responses.single().result.output.text shouldBe "Done"
            responses.single().metadata.containsKey(UsageTrackingChatModel.MAX_ITERATIONS_REACHED) shouldBe false
        }
    })

private data class LoopingRun(
    val responses: List<ChatResponse>,
    val prompts: List<Prompt>,
    val toolsExecuted: Int,
    val accumulator: UsageAccumulator,
)
