package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.ModelOptionsUtils
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.http.ResponseEntity
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A request that appends to an agent's last request (as the side questions do) puts the same bytes on the wire
 * before what it appends: same messages, same tools in the same order, same options. Only the provider
 * transport is mocked; the Spring AI client and its OpenAI serialization are real.
 */
class CachePrefixWireSpec :
    StringSpec({
        val nativeUsage = OpenAiApi.Usage(5, 10, 15, null, null)

        fun message(
            text: String?,
            toolCall: Boolean,
        ) = ChatCompletionMessage(
            text,
            ChatCompletionMessage.Role.ASSISTANT,
            null,
            null,
            if (toolCall) {
                listOf(ChatCompletionMessage.ToolCall("call-1", "function", ChatCompletionMessage.ChatCompletionFunction("lookup", "{}")))
            } else {
                emptyList()
            },
            null,
            null,
            null,
            null,
        )

        fun reason(toolCall: Boolean) =
            if (toolCall) OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS else OpenAiApi.ChatCompletionFinishReason.STOP

        /** A provider whose answers are given by [reply] (text, asks for the tool), recording what it received. */
        class Provider(
            reply: (Int) -> Pair<String?, Boolean>,
        ) {
            val requests = CopyOnWriteArrayList<ChatCompletionRequest>()
            val api = mockk<OpenAiApi>()

            init {
                every { api.chatCompletionEntity(any(), any()) } answers {
                    requests.add(firstArg())
                    val (text, toolCall) = reply(requests.size - 1)
                    ResponseEntity.ok(
                        OpenAiApi.ChatCompletion(
                            "id",
                            listOf(OpenAiApi.ChatCompletion.Choice(reason(toolCall), 0, message(text, toolCall), null)),
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
                    requests.add(firstArg())
                    val (text, toolCall) = reply(requests.size - 1)
                    Flux.just(
                        OpenAiApi.ChatCompletionChunk(
                            "id",
                            listOf(OpenAiApi.ChatCompletionChunk.ChunkChoice(reason(toolCall), 0, message(text, toolCall), null)),
                            0L,
                            "test-model",
                            null,
                            null,
                            "chat.completion.chunk",
                            nativeUsage,
                        ),
                    )
                }
            }

            fun chatClientProvider(usageEnabled: Boolean): ChatClientProvider {
                val nativeModel =
                    OpenAiChatModel
                        .builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model("test-model").temperature(0.3).reasoningEffort("high").build())
                        .build()
                val factory =
                    mockk<ChatModelFactory> {
                        every { createChatModel(any(), any(), any(), any(), any(), any(), any(), any()) } returns nativeModel
                    }
                return ChatClientProvider(factory, usageConfig = UsageConfigProperties(usageEnabled))
            }
        }

        val provider = AiProvider(namespaceId = UUID.randomUUID(), name = "test-provider", apiType = AiApiType.OpenAI)
        val model =
            AiModel(
                aiProviderId = provider.id,
                apiModelName = "test-model",
                pricing = ModelPricing(inputMTokens = 1.0, outputMTokens = 1.0),
            )
        val caseId = UUID.randomUUID().toString()

        fun tool(
            name: String,
            executed: AtomicInteger,
        ): ToolCallback =
            object : ToolCallback {
                private val definition =
                    ToolDefinition
                        .builder()
                        .name(name)
                        .description("The $name tool")
                        .inputSchema("""{"type":"object","properties":{"q":{"type":"string"}}}""")
                        .build()

                override fun getToolDefinition(): ToolDefinition = definition

                override fun call(input: String): String {
                    executed.incrementAndGet()
                    return "$name result"
                }
            }

        fun json(value: Any?): String = value?.let(ModelOptionsUtils::toJsonString) ?: "null"

        "a side question after an agent turn with a tool round keeps the agent's last request as its exact prefix" {
            val executed = AtomicInteger()
            val tools = listOf(tool("lookup", executed), tool("search", executed))
            // The agent: a tool round, then an answer. The side question: asks for a tool, gets nothing run.
            val transport =
                Provider { index ->
                    when (index) {
                        0 -> null to true
                        1 -> "Done" to false
                        else -> "Here is the answer." to true
                    }
                }
            val clients = transport.chatClientProvider(usageEnabled = true)

            val lastSent = AtomicReference<List<Message>>()
            clients
                .getChatClient(model, provider, caseId, UsageAccumulator(), onRequest = { lastSent.set(it.instructions.toList()) })
                .prompt(Prompt(listOf(SystemMessage("You are the planner."), UserMessage("Status?"))))
                .toolCallbacks(tools)
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(3))!!
                .joinToString("") shouldBe "Done"
            executed.get() shouldBe 1

            val answer =
                clients
                    .getChatClient(model, provider, caseId, UsageAccumulator(), runTools = false)
                    .prompt(Prompt(lastSent.get() + AssistantMessage("Done") + UserMessage("By the way, which tool ran?")))
                    .toolCallbacks(tools.map { DefinitionOnlyToolCallback(it.toolDefinition) })
                    .call()
                    .content()

            answer shouldBe "Here is the answer."
            executed.get() shouldBe 1
            transport.requests.size shouldBe 3
            val agentLast = transport.requests[1]
            val sideQuestion = transport.requests[2]
            agentLast.messages().map { it.role().name } shouldBe listOf("SYSTEM", "USER", "ASSISTANT", "TOOL")
            json(sideQuestion.messages().take(agentLast.messages().size)) shouldBe json(agentLast.messages())
            sideQuestion.messages().size shouldBe agentLast.messages().size + 2
            json(sideQuestion.tools()) shouldBe json(agentLast.tools())
            json(sideQuestion.tools()).indexOf("\"lookup\"") shouldBeLessThan json(sideQuestion.tools()).indexOf("\"search\"")
            sideQuestion.model() shouldBe agentLast.model()
            sideQuestion.temperature() shouldBe agentLast.temperature()
            sideQuestion.reasoningEffort() shouldBe agentLast.reasoningEffort()
            json(sideQuestion.toolChoice()) shouldBe json(agentLast.toolChoice())
        }

        "with usage disabled, a client that must not run tools still runs none" {
            val executed = AtomicInteger()
            val transport = Provider { "Let me look." to true }

            val answer =
                transport
                    .chatClientProvider(usageEnabled = false)
                    .getChatClient(model, provider, caseId, runTools = false)
                    .prompt(Prompt(listOf(UserMessage("Status?"))))
                    .toolCallbacks(tool("lookup", executed))
                    .call()
                    .content()

            answer shouldBe "Let me look."
            executed.get() shouldBe 0
            transport.requests.size shouldBe 1
        }
    })
