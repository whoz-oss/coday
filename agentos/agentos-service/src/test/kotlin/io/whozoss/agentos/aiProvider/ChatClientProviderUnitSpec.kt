package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.kotest.assertions.throwables.shouldThrow
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ChatClientProviderUnitSpec :
    StringSpec({

        val chatModelFactory = mockk<ChatModelFactory>()
        val chatClientProvider = ChatClientProvider(chatModelFactory)

        val aiProviderId = UUID.randomUUID()

        fun provider(
            apiKey: String? = "sk-test",
            apiType: AiApiType = AiApiType.Anthropic,
            baseUrl: String = "https://api.anthropic.com",
        ) = AiProvider(
            metadata = EntityMetadata(id = aiProviderId),
            namespaceId = UUID.randomUUID(),
            name = "${apiType.name.lowercase()}-prod",
            apiType = apiType,
            baseUrl = baseUrl,
            apiKey = apiKey,
        )

        fun model(
            apiName: String = "claude-sonnet-4-5",
            temperature: Double? = 0.7,
            maxCompletionTokens: Int? = null,
            reasoningEffort: ReasoningEffort? = null,
        ) = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = aiProviderId,
            apiModelName = apiName,
            temperature = temperature,
            maxCompletionTokens = maxCompletionTokens,
            reasoningEffort = reasoningEffort,
        )

        "getChatClient forwards model and provider fields to ChatModelFactory" {
            val m = model(apiName = "claude-sonnet-4-5", temperature = 0.3, maxCompletionTokens = 8192)
            val p = provider()
            val chatModel = mockk<ChatModel>(relaxed = true)

            every {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.Anthropic,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-test",
                    modelName = "claude-sonnet-4-5",
                    temperature = 0.3,
                    maxCompletionTokens = 8192,
                )
            } returns chatModel

            val client = chatClientProvider.getChatClient(m, p)

            client.shouldNotBeNull()
            verify(exactly = 1) {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.Anthropic,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-test",
                    modelName = "claude-sonnet-4-5",
                    temperature = 0.3,
                    maxCompletionTokens = 8192,
                )
            }
        }

        "getChatClient passes null temperature and maxCompletionTokens when model does not specify them" {
            val m = model(apiName = "gpt-4o", temperature = null, maxCompletionTokens = null)
            val p = provider()
            val chatModel = mockk<ChatModel>(relaxed = true)

            every {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.Anthropic,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-test",
                    modelName = "gpt-4o",
                    temperature = null,
                    maxCompletionTokens = null,
                )
            } returns chatModel

            chatClientProvider.getChatClient(m, p).shouldNotBeNull()
        }

        "getChatClient forwards the model reasoning effort to ChatModelFactory" {
            val m = model(apiName = "openai/gpt-6-astra", temperature = null, reasoningEffort = ReasoningEffort.XHIGH)
            val p = provider(apiType = AiApiType.OpenAI, baseUrl = "https://router.eu.requesty.ai")
            val chatModel = mockk<ChatModel>(relaxed = true)

            every {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.OpenAI,
                    baseUrl = "https://router.eu.requesty.ai",
                    apiKey = "sk-test",
                    modelName = "openai/gpt-6-astra",
                    temperature = null,
                    maxCompletionTokens = null,
                    reasoningEffort = ReasoningEffort.XHIGH,
                )
            } returns chatModel

            chatClientProvider.getChatClient(m, p).shouldNotBeNull()
            verify(exactly = 1) {
                chatModelFactory.createChatModel(
                    apiType = AiApiType.OpenAI,
                    baseUrl = "https://router.eu.requesty.ai",
                    apiKey = "sk-test",
                    modelName = "openai/gpt-6-astra",
                    temperature = null,
                    maxCompletionTokens = null,
                    reasoningEffort = ReasoningEffort.XHIGH,
                )
            }
        }

        // The tool-round cap: the namespace's value when given, else the platform's. A model that answers
        // with a tool call forever lets the number of executed tools show which cap applied.
        fun toolExecutionsUntilCapped(
            limits: LimitsConfigProperties,
            namespaceMaxToolRounds: Int?,
        ): Int {
            val executed = AtomicInteger()
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
                        executed.incrementAndGet()
                        return "tool result"
                    }
                }
            val toolCall =
                AssistantMessage
                    .builder()
                    .content("")
                    .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
                    .build()
            val nativeModel = mockk<ChatModel>()
            every { nativeModel.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { nativeModel.call(any<Prompt>()) } returns ChatResponse(listOf(Generation(toolCall)))
            val factory =
                mockk<ChatModelFactory> {
                    every { createChatModel(any(), any(), any(), any(), any(), any(), any(), any()) } returns nativeModel
                }
            val client =
                ChatClientProvider(factory, limits, UsageConfigProperties(enabled = true))
                    .getChatClient(
                        model(),
                        provider(),
                        accumulator = UsageAccumulator(),
                        maxToolRounds = namespaceMaxToolRounds,
                    )

            shouldThrow<IllegalStateException> { client.prompt("Hello").toolCallbacks(callback).call().content() }
            return executed.get()
        }

        "the namespace tool rounds override the platform value" {
            toolExecutionsUntilCapped(LimitsConfigProperties(agentMaxIterations = 1000), namespaceMaxToolRounds = 2) shouldBe 2
        }

        "a namespace without tool rounds inherits the platform value" {
            toolExecutionsUntilCapped(LimitsConfigProperties(agentMaxIterations = 3), namespaceMaxToolRounds = null) shouldBe 3
        }

        "enabled accounting still preserves native model behavior when no accumulator is supplied" {
            val nativeModel = mockk<ChatModel>()
            every { nativeModel.defaultOptions } returns ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(true)
                .build()
            every { nativeModel.call(any<Prompt>()) } answers {
                (firstArg<Prompt>().options as ToolCallingChatOptions).internalToolExecutionEnabled shouldBe true
                ChatResponse(listOf(Generation(AssistantMessage("Done"))))
            }
            val factory = mockk<ChatModelFactory> {
                every { createChatModel(any(), any(), any(), any(), any(), any(), any(), any()) } returns nativeModel
            }
            val clientProvider = ChatClientProvider(factory, usageConfig = UsageConfigProperties(enabled = true))
            val client = clientProvider.getChatClient(model(), provider())

            client.prompt("Hello").call().content() shouldBe "Done"
            verify(exactly = 1) { nativeModel.call(any<Prompt>()) }
        }

    })
