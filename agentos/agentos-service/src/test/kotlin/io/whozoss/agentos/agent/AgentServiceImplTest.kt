package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistry
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class AgentServiceImplTest :
    DescribeSpec({

        describe("AgentServiceImpl") {

            lateinit var chatClientProvider: ChatClientProvider
            lateinit var toolRegistry: ToolRegistry
            lateinit var aiModelRegistry: AiModelRegistry
            lateinit var agentService: AgentServiceImpl

            beforeEach {
                chatClientProvider = mockk()
                toolRegistry = mockk()
                aiModelRegistry = mockk()
                agentService = AgentServiceImpl(chatClientProvider, toolRegistry, aiModelRegistry)

                every { toolRegistry.listTools() } returns emptyList()
            }

            // The key scenario: name and modelName are intentionally different.
            // "my-agent" is the logical name used to look up the ChatClient,
            // while "gpt-4o-2024-08-06" is the underlying provider model identifier.
            // The bug was using model.modelName ("gpt-4o-2024-08-06") instead of
            // model.name ("my-agent"), causing a lookup failure in ChatClientProvider.
            fun modelWithDistinctNameAndModelName() =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "my-agent",
                    description = "An agent whose name differs from its provider model identifier",
                    modelName = "gpt-4o-2024-08-06",
                    providerName = "openai",
                )

            describe("findAgentByName") {

                it("calls getChatClient with model.name, not model.modelName") {
                    val model = modelWithDistinctNameAndModelName()
                    val chatClient = mockk<ChatClient>(relaxed = true)

                    every { aiModelRegistry.findByName("my-agent") } returns model
                    every { chatClientProvider.getChatClient("my-agent") } returns chatClient

                    agentService.findAgentByName("my-agent")

                    // Must be called with the logical name, not the provider model identifier
                    verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
                    verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
                }

                it("falls back to contains-match and still uses model.name for getChatClient") {
                    val model = modelWithDistinctNameAndModelName()
                    val chatClient = mockk<ChatClient>(relaxed = true)

                    every { aiModelRegistry.findByName("agent") } returns null
                    every { aiModelRegistry.getAll() } returns listOf(model)
                    every { chatClientProvider.getChatClient("my-agent") } returns chatClient

                    agentService.findAgentByName("agent")

                    verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
                    verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
                }

                it("throws when no model matches the given name") {
                    every { aiModelRegistry.findByName("unknown") } returns null
                    every { aiModelRegistry.getAll() } returns emptyList()

                    shouldThrow<IllegalArgumentException> {
                        agentService.findAgentByName("unknown")
                    }
                }
            }

            describe("listAgents") {

                it("calls getChatClient with model.name for every registered model") {
                    val model1 = AiModel(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "agent-alpha",
                        description = "First agent",
                        modelName = "claude-3-5-sonnet-20241022",
                        providerName = "anthropic",
                    )
                    val model2 = AiModel(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "agent-beta",
                        description = "Second agent",
                        modelName = "gpt-4o-mini",
                        providerName = "openai",
                    )
                    val chatClient = mockk<ChatClient>(relaxed = true)

                    every { aiModelRegistry.getAll() } returns listOf(model1, model2)
                    every { chatClientProvider.getChatClient(any<String>()) } returns chatClient

                    agentService.listAgents()

                    verify(exactly = 1) { chatClientProvider.getChatClient("agent-alpha") }
                    verify(exactly = 1) { chatClientProvider.getChatClient("agent-beta") }
                    // The underlying provider model identifiers must never be used
                    verify(exactly = 0) { chatClientProvider.getChatClient("claude-3-5-sonnet-20241022") }
                    verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-mini") }
                }
            }

            describe("getDefaultAgent") {

                it("returns null when no default model is registered") {
                    every { aiModelRegistry.getDefault() } returns null

                    val agent = agentService.getDefaultAgent()

                    agent shouldBe null
                }

                it("calls getChatClient with model.name for the default model") {
                    val model = modelWithDistinctNameAndModelName()
                    val chatClient = mockk<ChatClient>(relaxed = true)

                    every { aiModelRegistry.getDefault() } returns model
                    every { chatClientProvider.getChatClient("my-agent") } returns chatClient

                    agentService.getDefaultAgent()

                    verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
                    verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
                }
            }
        }
    })
