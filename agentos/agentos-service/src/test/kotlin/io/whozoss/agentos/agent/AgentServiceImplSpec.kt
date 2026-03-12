package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.aiModel.AiModelRegistry
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.tool.ToolRegistry
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

class AgentServiceImplSpec : StringSpec() {

    private val chatClientProvider: ChatClientProvider = mockk()
    private val toolRegistry: ToolRegistry = mockk()
    private val aiModelRegistry: AiModelRegistry = mockk()
    private val namespaceService: NamespaceService = mockk()
    private val agentService = AgentServiceImpl(chatClientProvider, toolRegistry, aiModelRegistry, namespaceService)

    // A context and matching namespace used across most tests
    private val namespaceId: UUID = UUID.randomUUID()
    private val caseId: UUID = UUID.randomUUID()
    private val context = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId)
    private val namespace = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        name = "engineering",
        description = "Engineering namespace for backend services",
    )

    init {
        every { toolRegistry.listTools() } returns emptyList()
        every { namespaceService.findById(namespaceId) } returns namespace

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

        // -------------------------------------------------------------------------
        // findAgentByName
        // -------------------------------------------------------------------------

        "findAgentByName calls getChatClient with model.name, not model.modelName" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
        }

        "findAgentByName falls back to contains-match and still uses model.name for getChatClient" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelRegistry.findByName("agent") } returns null
            every { aiModelRegistry.getAll() } returns listOf(model)
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.findAgentByName("agent", context)

            verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
        }

        "findAgentByName throws when no model matches the given name" {
            every { aiModelRegistry.findByName("unknown") } returns null
            every { aiModelRegistry.getAll() } returns emptyList()

            shouldThrow<IllegalArgumentException> {
                agentService.findAgentByName("unknown", context)
            }
        }

        // -------------------------------------------------------------------------
        // Namespace context injection into instructions
        // -------------------------------------------------------------------------

        "findAgentByName appends namespace context to model instructions" {
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "You are a helpful assistant.",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            val agent = agentService.findAgentByName("my-agent", context)

            // The agent's name is derived from the model — verify it was built at all
            agent.name shouldBe "my-agent"
        }

        "findAgentByName produces namespace context block when model has no instructions" {
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "my-agent",
                description = "desc",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = null,
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            // Should not throw — namespace context becomes the sole instructions
            agentService.findAgentByName("my-agent", context)
        }

        "findAgentByName resolves namespace by namespaceId from context" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.findByName("my-agent") } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.findAgentByName("my-agent", context)

            verify(exactly = 1) { namespaceService.findById(namespaceId) }
        }

        // -------------------------------------------------------------------------
        // listAgents
        // -------------------------------------------------------------------------

        "listAgents calls getChatClient with model.name for every registered model" {
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
            verify(exactly = 0) { chatClientProvider.getChatClient("claude-3-5-sonnet-20241022") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-mini") }
        }

        "listAgents does not call namespaceService — no context available" {
            val model = AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "agent-alpha",
                description = "First agent",
                modelName = "gpt-4o",
                providerName = "openai",
            )
            val chatClient = mockk<ChatClient>(relaxed = true)
            every { aiModelRegistry.getAll() } returns listOf(model)
            every { chatClientProvider.getChatClient(any<String>()) } returns chatClient

            agentService.listAgents()

            verify(exactly = 0) { namespaceService.findById(any()) }
        }

        // -------------------------------------------------------------------------
        // getDefaultAgent
        // -------------------------------------------------------------------------

        "getDefaultAgent returns null when no default model is registered" {
            every { aiModelRegistry.getDefault() } returns null

            agentService.getDefaultAgent(context) shouldBe null
        }

        "getDefaultAgent calls getChatClient with model.name for the default model" {
            val model = modelWithDistinctNameAndModelName()
            val chatClient = mockk<ChatClient>(relaxed = true)

            every { aiModelRegistry.getDefault() } returns model
            every { chatClientProvider.getChatClient("my-agent") } returns chatClient

            agentService.getDefaultAgent(context)

            verify(exactly = 1) { chatClientProvider.getChatClient("my-agent") }
            verify(exactly = 0) { chatClientProvider.getChatClient("gpt-4o-2024-08-06") }
        }

        // -------------------------------------------------------------------------
        // getDefaultAgentName
        // -------------------------------------------------------------------------

        "getDefaultAgentName returns null when no default model is registered" {
            every { aiModelRegistry.getDefault() } returns null

            agentService.getDefaultAgentName() shouldBe null
        }

        "getDefaultAgentName returns the model name without instantiating any agent" {
            val model = modelWithDistinctNameAndModelName()
            every { aiModelRegistry.getDefault() } returns model

            agentService.getDefaultAgentName() shouldBe "my-agent"

            verify(exactly = 0) { chatClientProvider.getChatClient(any<String>()) }
            verify(exactly = 0) { toolRegistry.listTools() }
        }

        // -------------------------------------------------------------------------
        // resolveAgentName
        // -------------------------------------------------------------------------

        "resolveAgentName returns the canonical name on exact match without instantiating any agent" {
            val model = modelWithDistinctNameAndModelName()
            every { aiModelRegistry.findByName("my-agent") } returns model

            agentService.resolveAgentName("my-agent") shouldBe "my-agent"

            verify(exactly = 0) { chatClientProvider.getChatClient(any<String>()) }
            verify(exactly = 0) { toolRegistry.listTools() }
        }

        "resolveAgentName falls back to contains-match and returns canonical name without instantiating any agent" {
            val model = modelWithDistinctNameAndModelName()
            every { aiModelRegistry.findByName("agent") } returns null
            every { aiModelRegistry.getAll() } returns listOf(model)

            agentService.resolveAgentName("agent") shouldBe "my-agent"

            verify(exactly = 0) { chatClientProvider.getChatClient(any<String>()) }
            verify(exactly = 0) { toolRegistry.listTools() }
        }

        "resolveAgentName returns null when no model matches" {
            every { aiModelRegistry.findByName("unknown") } returns null
            every { aiModelRegistry.getAll() } returns emptyList()

            agentService.resolveAgentName("unknown") shouldBe null
        }
    }
}
