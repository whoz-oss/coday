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
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.UUID

class AgentAdvancedTest : StringSpec({
    timeout = 5000

    /**
     * Stub the streaming path that [AgentAdvanced.streamToText] uses.
     *
     * All LLM calls in [AgentAdvanced] now go through `stream().content()` (a Reactor
     * [Flux]) rather than the blocking `.call().content()`. The test must match that
     * path so the stubs are actually invoked.
     *
     * [responses] is a list of strings returned in order: first call returns [0],
     * second call returns [1], etc. If there are more calls than responses the last
     * entry is repeated.
     */
    fun stubStreamResponses(mockChatClient: ChatClient, vararg responses: String) {
        var callIndex = 0
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } answers {
            val text = responses.getOrElse(callIndex) { responses.last() }
            callIndex++
            Flux.just(text)
        }
    }

    fun makeAgent(
        agentId: UUID,
        chatClient: ChatClient,
        tools: List<StandardTool<*>> = emptyList(),
        maxIterations: Int = 5,
    ): AgentAdvanced {
        val model = AiModel(
            metadata = EntityMetadata(id = agentId),
            name = "TestAgent",
            description = "Test agent for advanced orchestration",
            modelName = "gpt-4o",
            providerName = "openai",
        )
        return AgentAdvanced(
            metadata = EntityMetadata(id = agentId),
            model = model,
            chatClient = chatClient,
            tools = tools,
            maxIterations = maxIterations,
        )
    }

    fun userMessage(projectId: UUID, caseId: UUID, text: String) =
        MessageEvent(
            projectId = projectId,
            caseId = caseId,
            actor = Actor("user1", "User One", ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
        )

    "should complete full orchestration loop with Answer tool" {
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        // Call 1 (generateIntention) -> intention text
        // Call 2 (selectTool)        -> "Answer"
        stubStreamResponses(
            mockChatClient,
            "I should call the Answer tool to respond to the user",
            "Answer",
        )

        val answerTool = mockk<StandardTool<Nothing>>()
        every { answerTool.name } returns "Answer"
        every { answerTool.description } returns "Provides the final answer to the user"
        every { answerTool.inputSchema } returns "{}"
        every { answerTool.version } returns "1.0"
        every { answerTool.paramType } returns null
        every { answerTool.execute(null) } returns "Hello! How can I help you?"

        val agent = makeAgent(agentId, mockChatClient, tools = listOf(answerTool))

        val events = agent.run(listOf(userMessage(projectId, caseId, "Hello, can you help me?"))).toList()

        events shouldHaveSize 5

        val runningEvent = events[0] as? AgentRunningEvent
        runningEvent shouldNotBe null
        runningEvent!!.agentId shouldBe agentId
        runningEvent.agentName shouldBe "TestAgent"

        events[1] as? ThinkingEvent shouldNotBe null

        val intentionEvent = events[2] as? IntentionGeneratedEvent
        intentionEvent shouldNotBe null
        intentionEvent!!.agentId shouldBe agentId
        intentionEvent.intention shouldContain "Answer"

        val toolSelectedEvent = events[3] as? ToolSelectedEvent
        toolSelectedEvent shouldNotBe null
        toolSelectedEvent!!.agentId shouldBe agentId
        toolSelectedEvent.toolName shouldBe "Answer"

        val finishedEvent = events[4] as? AgentFinishedEvent
        finishedEvent shouldNotBe null
        finishedEvent!!.agentId shouldBe agentId
        finishedEvent.agentName shouldBe "TestAgent"
    }

    "should stop immediately when shouldContinue is false on entry" {
        // Verifies that AgentAdvanced honours the pre-loop shouldContinue guard.
        // The LLM must never be called.
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        // No stubs needed — LLM should not be called at all.

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(
            listOf(userMessage(projectId, caseId, "hello")),
            shouldContinue = { false },
        ).toList()

        // AgentRunningEvent is emitted before the while-loop guard, so it appears.
        // Nothing else should be emitted except AgentFinishedEvent.
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
        events.filterIsInstance<ThinkingEvent>().size shouldBe 0
        events.filterIsInstance<IntentionGeneratedEvent>().size shouldBe 0
    }

    "should stop mid-iteration when shouldContinue flips to false after first intention" {
        // The shouldContinue lambda returns true for the first check (entering the loop)
        // but false after that, so the agent stops after emitting the intention but
        // before calling selectTool.
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        var checks = 0
        // true  -> while condition (enters loop)
        // true  -> first if (!shouldContinue()) check (before generateIntention)
        // false -> second if (!shouldContinue()) check (before selectTool)
        val shouldContinue: () -> Boolean = { checks++ < 2 }

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        // Only generateIntention calls the LLM before the guard fires.
        stubStreamResponses(mockChatClient, "I will look up the answer")

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(
            listOf(userMessage(projectId, caseId, "hello")),
            shouldContinue = shouldContinue,
        ).toList()

        // Intention was generated, but selectTool was never called.
        events.filterIsInstance<IntentionGeneratedEvent>().size shouldBe 1
        events.filterIsInstance<ToolSelectedEvent>().size shouldBe 0
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }

    "should emit WarnEvent and finish on LLM error" {
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.error(RuntimeException("LLM unavailable"))

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(listOf(userMessage(projectId, caseId, "hello"))).toList()

        events.filterIsInstance<WarnEvent>().first().message shouldContain "Error"
        // AgentFinishedEvent is NOT emitted by AgentAdvanced when an exception escapes
        // the catch block — that is intentional (the runtime handles ERROR status).
        // But the WarnEvent must be present.
    }
})
