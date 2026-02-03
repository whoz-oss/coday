package io.whozoss.agentos.agent

import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.orchestration.AgentSimple
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.model.Actor
import io.whozoss.agentos.sdk.model.ActorRole
import io.whozoss.agentos.sdk.model.AgentFinishedEvent
import io.whozoss.agentos.sdk.model.AgentModel
import io.whozoss.agentos.sdk.model.AgentRunningEvent
import io.whozoss.agentos.sdk.model.MessageContent
import io.whozoss.agentos.sdk.model.MessageEvent
import io.whozoss.agentos.sdk.model.StandardTool
import io.whozoss.agentos.sdk.model.TextChunkEvent
import io.whozoss.agentos.sdk.model.ThinkingEvent
import io.whozoss.agentos.sdk.model.WarnEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AgentSimpleTest {
    @Test
    fun `should complete with single LLM call`() =
        runBlocking {
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
                AgentModel(
                    metadata = EntityMetadata(id = agentId),
                    name = "SimpleAgent",
                    description = "A simple test agent",
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
            assertTrue(events.size >= 4, "Should emit at least 4 events")

            // Event 0: AgentRunningEvent
            val runningEvent = events[0] as? AgentRunningEvent
            assertNotNull(runningEvent, "First event should be AgentRunningEvent")
            assertEquals(agentId, runningEvent.agentId)
            assertEquals("SimpleAgent", runningEvent.agentName)

            // Event 1: ThinkingEvent
            val thinkingEvent = events[1] as? ThinkingEvent
            assertNotNull(thinkingEvent, "Second event should be ThinkingEvent")

            // TextChunkEvents
            val textChunkEvents = events.filterIsInstance<TextChunkEvent>()
            assertTrue(textChunkEvents.isNotEmpty(), "Should have TextChunkEvents")
            assertEquals(3, textChunkEvents.size, "Should have 3 text chunks")

            // MessageEvent (assistant response)
            val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
            assertNotNull(messageEvent, "Should have MessageEvent")
            assertEquals(ActorRole.AGENT, messageEvent.actor.role)
            assertEquals(agentId.toString(), messageEvent.actor.id)
            assertEquals("SimpleAgent", messageEvent.actor.displayName)
            val textContent = messageEvent.content.filterIsInstance<MessageContent.Text>().firstOrNull()
            assertNotNull(textContent)
            assertTrue(textContent.content.contains("Hello"), "Response should contain greeting")

            // AgentFinishedEvent
            val finishedEvent = events.last() as? AgentFinishedEvent
            assertNotNull(finishedEvent, "Last event should be AgentFinishedEvent")
            assertEquals(agentId, finishedEvent.agentId)
            assertEquals("SimpleAgent", finishedEvent.agentName)
        }

    @Test
    fun `should handle conversation with multiple messages`() =
        runBlocking {
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
                AgentModel(
                    metadata = EntityMetadata(id = agentId),
                    name = "SimpleAgent",
                    description = "A simple test agent",
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
            assertTrue(events.size >= 4, "Should have at least 4 events")

            val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
            assertNotNull(messageEvent)
            assertTrue(
                messageEvent.content
                    .filterIsInstance<MessageContent.Text>()
                    .first()
                    .content
                    .contains("previous"),
                "Response should reference previous conversation",
            )
        }

    @Test
    fun `should handle error gracefully`() =
        runBlocking {
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
                AgentModel(
                    metadata = EntityMetadata(id = agentId),
                    name = "SimpleAgent",
                    description = "A simple test agent",
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
            assertTrue(events.size >= 3, "Should emit at least running, warn, and finished events")

            val warnEvent = events.filterIsInstance<WarnEvent>().firstOrNull()
            assertNotNull(warnEvent, "Should emit WarnEvent on error")
            assertTrue(warnEvent.message.contains("Error"), "Warn message should mention error")

            val finishedEvent = events.filterIsInstance<AgentFinishedEvent>().firstOrNull()
            assertNotNull(finishedEvent, "Should still emit AgentFinishedEvent after error")
        }

    @Test
    fun `should convert other agents to user messages`() =
        runBlocking {
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
                AgentModel(
                    metadata = EntityMetadata(id = agentId),
                    name = "SimpleAgent",
                    description = "A simple test agent",
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
            assertTrue(events.size >= 4, "Should have at least 4 events")

            val finishedEvent = events.last() as? AgentFinishedEvent
            assertNotNull(finishedEvent, "Should finish successfully")
        }
}
