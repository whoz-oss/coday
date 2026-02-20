package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.orchestration.AgentSimple
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.UUID

class AgentSimpleTest : StringSpec({
    timeout = 5000

    "should complete with single LLM call" {
        // Given
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        // Mock ChatClient with streaming support
        val mockChatClient = mockk<ChatClient>(relaxed = true)

        // Mock streaming response
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        val flux = Flux.just("Hello! ", "I can help you ", "with that.")

        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns flux

        // Mock tools (empty for this test)
        val tools = emptyList<StandardTool<*>>()

        // Create agent
        val model =
            AiModel(
                metadata = EntityMetadata(id = agentId),
                name = "SimpleAgent",
                description = "A simple test agent",
                modelName = "gpt-4o",
                providerName = "openai",
                instructions = "You are a helpful assistant.",
            )
        val agent =
            AgentSimple(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatClient = mockChatClient,
                tools = tools,
            )

        // Initial user message
        val initialEvents =
            listOf(
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("Hello, can you help me?")),
                ),
            )

        // When
        val events = agent.run(initialEvents).toList()

        // Then - Verify event sequence
        // Should emit: AgentRunningEvent, ThinkingEvent, TextChunkEvent(s), MessageEvent, AgentFinishedEvent
        events shouldHaveAtLeastSize 4

        // Event 0: AgentRunningEvent
        val runningEvent = events[0] as? AgentRunningEvent
        runningEvent shouldNotBe null
        runningEvent!!.agentId shouldBe agentId
        runningEvent.agentName shouldBe "SimpleAgent"

        // Event 1: ThinkingEvent
        val thinkingEvent = events[1] as? ThinkingEvent
        thinkingEvent shouldNotBe null

        // TextChunkEvents
        val textChunkEvents = events.filterIsInstance<TextChunkEvent>()
        textChunkEvents.size shouldBe 3

        // MessageEvent (assistant response)
        val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
        messageEvent shouldNotBe null
        messageEvent!!.actor.role shouldBe ActorRole.AGENT
        messageEvent.actor.id shouldBe agentId.toString()
        messageEvent.actor.displayName shouldBe "SimpleAgent"
        val textContent = messageEvent.content.filterIsInstance<MessageContent.Text>().firstOrNull()
        textContent shouldNotBe null
        textContent!!.content shouldContain "Hello"

        // AgentFinishedEvent
        val finishedEvent = events.last() as? AgentFinishedEvent
        finishedEvent shouldNotBe null
        finishedEvent!!.agentId shouldBe agentId
        finishedEvent.agentName shouldBe "SimpleAgent"
    }

    "should handle conversation with multiple messages" {
        // Given
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        // Mock ChatClient
        val mockChatClient = mockk<ChatClient>(relaxed = true)

        // Mock streaming response
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        val flux = Flux.just("Based on our ", "previous conversation, ", "here's my response.")

        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns flux

        val tools = emptyList<StandardTool<*>>()

        val model =
            AiModel(
                metadata = EntityMetadata(id = agentId),
                name = "SimpleAgent",
                description = "A simple test agent",
                modelName = "gpt-4o",
                providerName = "openai",
            )
        val agent =
            AgentSimple(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatClient = mockChatClient,
                tools = tools,
            )

        // Multiple messages in history
        val initialEvents =
            listOf(
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("First question")),
                ),
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor(agentId.toString(), "SimpleAgent", ActorRole.AGENT),
                    content = listOf(MessageContent.Text("First answer")),
                ),
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("Follow-up question")),
                ),
            )

        // When
        val events = agent.run(initialEvents).toList()

        // Then
        events shouldHaveAtLeastSize 4

        val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
        messageEvent shouldNotBe null
        messageEvent!!.content
            .filterIsInstance<MessageContent.Text>()
            .first()
            .content shouldContain "previous"
    }

    "should handle error gracefully" {
        // Given
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        // Mock ChatClient that throws exception
        val mockChatClient = mockk<ChatClient>(relaxed = true)

        // Mock streaming that throws error
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        val flux = Flux.error<String>(RuntimeException("API Error"))

        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns flux

        val tools = emptyList<StandardTool<*>>()

        val model =
            AiModel(
                metadata = EntityMetadata(id = agentId),
                name = "SimpleAgent",
                description = "A simple test agent",
                modelName = "gpt-4o",
                providerName = "openai",
            )
        val agent =
            AgentSimple(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatClient = mockChatClient,
                tools = tools,
            )

        val initialEvents =
            listOf(
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("Hello")),
                ),
            )

        // When
        val events = agent.run(initialEvents).toList()

        // Then - Should emit error warning and finish
        events shouldHaveAtLeastSize 3

        val warnEvent = events.filterIsInstance<WarnEvent>().firstOrNull()
        warnEvent shouldNotBe null
        warnEvent!!.message shouldContain "Error"

        val finishedEvent = events.filterIsInstance<AgentFinishedEvent>().firstOrNull()
        finishedEvent shouldNotBe null
    }

    "should convert other agents to user messages" {
        // Given
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val otherAgentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)

        // Mock streaming response
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        val flux = Flux.just("Response considering ", "other agent's input")

        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns flux

        val tools = emptyList<StandardTool<*>>()

        val model =
            AiModel(
                metadata = EntityMetadata(id = agentId),
                name = "SimpleAgent",
                description = "A simple test agent",
                modelName = "gpt-4o",
                providerName = "openai",
            )
        val agent =
            AgentSimple(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatClient = mockChatClient,
                tools = tools,
            )

        // Message from another agent
        val initialEvents =
            listOf(
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("Question")),
                ),
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor(otherAgentId.toString(), "OtherAgent", ActorRole.AGENT),
                    content = listOf(MessageContent.Text("Other agent's response")),
                ),
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor("user1", "User One", ActorRole.USER),
                    content = listOf(MessageContent.Text("Follow-up")),
                ),
            )

        // When
        val events = agent.run(initialEvents).toList()

        // Then - Should complete successfully (other agent converted to user)
        events shouldHaveAtLeastSize 4

        val finishedEvent = events.last() as? AgentFinishedEvent
        finishedEvent shouldNotBe null
    }
})
