package io.whozoss.agentos.aiProvider

import com.openai.models.completions.CompletionUsage
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
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the real Spring AI [org.springframework.ai.chat.client.ChatClient] and real tool execution; only the
 * provider is faked.
 *
 * The fake behaves like every Spring AI 2.x chat model: it returns tool calls to the caller and never executes
 * them. Tools are then executed either by [io.whozoss.agentos.chat.UsageTrackingChatModel] (usage accounting
 * enabled) or by the `ToolCallingAdvisor` that `ChatClient` installs by default (accounting disabled).
 */
class ChatClientUsageSwitchSpec :
    StringSpec({
        listOf(false, true).forEach { enabled ->
            listOf(false, true).forEach { streaming ->
                listOf(false, true).forEach { withTool ->
                    "usage $enabled supports ${if (streaming) "stream" else "call"} with tool=$withTool" {
                        val requests = AtomicInteger()
                        val toolsExecuted = AtomicInteger()
                        val gateChecks = AtomicInteger()
                        val sentEfforts = CopyOnWriteArrayList<String?>()
                        val callback =
                            object : ToolCallback {
                                override fun getToolDefinition(): ToolDefinition =
                                    ToolDefinition
                                        .builder()
                                        .name("lookup")
                                        .description("Return a test result")
                                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                                        .build()

                                override fun call(input: String): String {
                                    toolsExecuted.incrementAndGet()
                                    return "tool result"
                                }
                            }

                        fun nextResponse(prompt: Prompt): ChatResponse {
                            sentEfforts.add((prompt.options as? OpenAiChatOptions)?.reasoningEffort)
                            val toolRound = requests.getAndIncrement() == 0 && withTool
                            val message =
                                AssistantMessage
                                    .builder()
                                    .content(if (toolRound) "" else "Done")
                                    .toolCalls(
                                        if (toolRound) {
                                            listOf(
                                                AssistantMessage.ToolCall("call-1", "function", "lookup", "{}"),
                                            )
                                        } else {
                                            emptyList()
                                        },
                                    ).build()
                            val finishReason = if (toolRound) "TOOL_CALLS" else "STOP"
                            val nativeUsage =
                                CompletionUsage
                                    .builder()
                                    .promptTokens(10)
                                    .completionTokens(5)
                                    .totalTokens(15)
                                    .build()
                            return ChatResponse(
                                listOf(Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                                ChatResponseMetadata.builder().usage(DefaultUsage(10, 5, 15, nativeUsage)).build(),
                            )
                        }
                        val nativeModel =
                            object : ChatModel {
                                override fun call(prompt: Prompt): ChatResponse = nextResponse(prompt)

                                override fun stream(prompt: Prompt): Flux<ChatResponse> = Flux.defer { Flux.just(nextResponse(prompt)) }

                                override fun getOptions(): ChatOptions =
                                    OpenAiChatOptions
                                        .builder()
                                        .model("test-model")
                                        .reasoningEffort("high")
                                        .build()
                            }
                        val factory =
                            mockk<ChatModelFactory> {
                                every { createChatModel(any(), any(), any(), any(), any(), any(), any(), any()) } returns nativeModel
                            }
                        val provider =
                            AiProvider(
                                namespaceId = UUID.randomUUID(),
                                name = "test-provider",
                                apiType = AiApiType.OpenAI,
                            )
                        val model =
                            AiModel(
                                aiProviderId = provider.id,
                                apiModelName = "test-model",
                                pricing = ModelPricing(inputMTokens = 1_000_000.0, outputMTokens = 1_000_000.0),
                            )
                        // Supplying an accumulator while OFF must still bypass accounting and its gate.
                        val accumulator =
                            UsageAccumulator().also {
                                it.beforeCall = {
                                    check(enabled) { "Accounting gate was invoked while usage is disabled" }
                                    gateChecks.incrementAndGet()
                                    CompletableFuture.completedFuture(null)
                                }
                            }
                        val client =
                            ChatClientProvider(factory, usageConfig = UsageConfigProperties(enabled))
                                .getChatClient(model, provider, accumulator = accumulator)
                        // AgentSimple attaches resolved tool callbacks to each prompt, not to model defaults.
                        val prompt = client.prompt("Hello")
                        if (withTool) prompt.toolCallbacks(callback)
                        val content =
                            if (streaming) {
                                prompt
                                    .stream()
                                    .content()
                                    .collectList()
                                    .block(Duration.ofSeconds(3))!!
                                    .joinToString("")
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
                        // The model's reasoning effort survives the options copies, including the tool round.
                        sentEfforts shouldBe List(requests.get()) { "high" }
                    }
                }
            }
        }
    })
