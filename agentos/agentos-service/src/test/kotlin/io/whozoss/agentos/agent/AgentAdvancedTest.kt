package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolSelectedEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

class AgentAdvancedTest : StringSpec({
    timeout = 5000

    "should complete full orchestration loop with Answer tool" {
        // Given
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        // Mock ChatClient with relaxed mocking
        val mockChatClient = mockk<ChatClient>(relaxed = true)

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
        val mockAgentService = mockk<AgentService>()

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
        val model =
            AiModel(
                metadata = EntityMetadata(id = agentId),
                name = "TestAgent",
                description = "Test agent for advanced orchestration",
            )
        val agent =
            AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatClient = mockChatClient,
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
        events shouldHaveSize 5

        // Event 0: AgentRunningEvent
        val runningEvent = events[0] as? AgentRunningEvent
        runningEvent shouldNotBe null
        runningEvent!!.agentId shouldBe agentId
        runningEvent.agentName shouldBe "TestAgent"

        // Event 1: ThinkingEvent
        val thinkingEvent = events[1] as? ThinkingEvent
        thinkingEvent shouldNotBe null

        // Event 2: IntentionGeneratedEvent
        val intentionEvent = events[2] as? IntentionGeneratedEvent
        intentionEvent shouldNotBe null
        intentionEvent!!.agentId shouldBe agentId
        intentionEvent.intention shouldContain "Answer"

        // Event 3: ToolSelectedEvent
        val toolSelectedEvent = events[3] as? ToolSelectedEvent
        toolSelectedEvent shouldNotBe null
        toolSelectedEvent!!.agentId shouldBe agentId
        toolSelectedEvent.toolName shouldBe "Answer"

        // Event 4: AgentFinishedEvent
        val finishedEvent = events[4] as? AgentFinishedEvent
        finishedEvent shouldNotBe null
        finishedEvent!!.agentId shouldBe agentId
        finishedEvent.agentName shouldBe "TestAgent"
    }
})
