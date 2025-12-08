package io.biznet.agentos.orchestration

import io.biznet.agentos.tools.domain.StandardTool
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class AgentAdvancedTest {
    @Test
    fun `should complete full orchestration loop with Answer tool`() =
        runBlocking {
            // Given
            val projectId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            // Mock ChatClient with relaxed mocking
            val mockChatClient = mockk<ChatClient>(relaxed = true)

            // Mock ChatClient.Builder
            val mockChatClientBuilder = mockk<ChatClient.Builder>()
            every { mockChatClientBuilder.build() } returns mockChatClient

            // Create a response counter to return different values
            var callCount = 0
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } answers {
                when (callCount++) {
                    0 -> "I should call the Answer tool to respond to the user" // Intention
                    else -> "Answer" // Tool selection
                }
            }

            // Mock IAgentService
            val mockAgentService = mockk<IAgentService>()

            // Mock Answer tool
            val answerTool = mockk<StandardTool<Nothing>>()
            every { answerTool.name } returns "Answer"
            every { answerTool.description } returns "Provides the final answer to the user"
            every { answerTool.inputSchema } returns "{}"
            every { answerTool.version } returns "1.0"
            every { answerTool.paramType } returns null
            every { answerTool.execute(null) } returns "Hello! How can I help you?"

            val tools = listOf(answerTool)

            // Create agent
            val agent =
                AgentAdvanced(
                    id = agentId,
                    name = "TestAgent",
                    chatClientBuilder = mockChatClientBuilder,
                    tools = tools,
                    agentService = mockAgentService,
                    maxIterations = 5,
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
            assertEquals(5, events.size, "Should emit exactly 5 events")

            // Event 0: AgentRunningEvent
            val runningEvent = events[0] as? AgentRunningEvent
            assertNotNull(runningEvent, "First event should be AgentRunningEvent")
            assertEquals(agentId, runningEvent.agentId)
            assertEquals("TestAgent", runningEvent.agentName)

            // Event 1: ThinkingEvent
            val thinkingEvent = events[1] as? ThinkingEvent
            assertNotNull(thinkingEvent, "Second event should be ThinkingEvent")

            // Event 2: IntentionGeneratedEvent
            val intentionEvent = events[2] as? IntentionGeneratedEvent
            assertNotNull(intentionEvent, "Third event should be IntentionGeneratedEvent")
            assertEquals(agentId, intentionEvent.agentId)
            assertTrue(intentionEvent.intention.contains("Answer"), "Intention should mention Answer tool")

            // Event 3: ToolSelectedEvent
            val toolSelectedEvent = events[3] as? ToolSelectedEvent
            assertNotNull(toolSelectedEvent, "Fourth event should be ToolSelectedEvent")
            assertEquals(agentId, toolSelectedEvent.agentId)
            assertEquals("Answer", toolSelectedEvent.toolName)

            // Event 4: AgentFinishedEvent
            val finishedEvent = events[4] as? AgentFinishedEvent
            assertNotNull(finishedEvent, "Fifth event should be AgentFinishedEvent")
            assertEquals(agentId, finishedEvent.agentId)
            assertEquals("TestAgent", finishedEvent.agentName)
        }
}
