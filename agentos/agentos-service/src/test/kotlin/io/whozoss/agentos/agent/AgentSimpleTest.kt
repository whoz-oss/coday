package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.orchestration.AgentSimple
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
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

    fun makeAgent(
        agentId: UUID,
        chatClient: ChatClient,
        name: String = "SimpleAgent",
        tools: Collection<StandardTool<*>> = emptyList(),
        instructions: String? = null,
    ): AgentSimple {
        val model = AiModel(
            metadata = EntityMetadata(id = agentId),
            name = name,
            description = "A simple test agent",
            modelName = "gpt-4o",
            providerName = "openai",
            instructions = instructions,
        )
        return AgentSimple(
            metadata = EntityMetadata(id = agentId),
            model = model,
            chatClient = chatClient,
            tools = tools,
        )
    }

    fun userMessage(projectId: UUID, caseId: UUID, text: String) =
        MessageEvent(
            projectId = projectId,
            caseId = caseId,
            actor = Actor("user1", "User One", ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
        )

    "should complete with single LLM call" {
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.just("Hello! ", "I can help you ", "with that.")

        val agent = makeAgent(agentId, mockChatClient, instructions = "You are a helpful assistant.")

        val events = agent.run(listOf(userMessage(projectId, caseId, "Hello, can you help me?"))).toList()

        events shouldHaveAtLeastSize 4
        events[0] as? ThinkingEvent shouldNotBe null
        events.filterIsInstance<TextChunkEvent>().size shouldBe 3

        val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
        messageEvent shouldNotBe null
        messageEvent!!.actor.role shouldBe ActorRole.AGENT
        messageEvent.actor.id shouldBe agentId.toString()
        messageEvent.actor.displayName shouldBe "SimpleAgent"
        messageEvent.content.filterIsInstance<MessageContent.Text>().first().content shouldContain "Hello"

        val finishedEvent = events.last() as? AgentFinishedEvent
        finishedEvent shouldNotBe null
        finishedEvent!!.agentId shouldBe agentId
        finishedEvent.agentName shouldBe "SimpleAgent"
    }

    "should handle conversation with multiple messages" {
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.just("Based on our ", "previous conversation, ", "here's my response.")

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(
            listOf(
                userMessage(projectId, caseId, "First question"),
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor(agentId.toString(), "SimpleAgent", ActorRole.AGENT),
                    content = listOf(MessageContent.Text("First answer")),
                ),
                userMessage(projectId, caseId, "Follow-up question"),
            ),
        ).toList()

        events shouldHaveAtLeastSize 4
        events.filterIsInstance<MessageEvent>().first()
            .content.filterIsInstance<MessageContent.Text>().first()
            .content shouldContain "previous"
    }

    "should handle error gracefully" {
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.error(RuntimeException("API Error"))

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(listOf(userMessage(projectId, caseId, "Hello"))).toList()

        events shouldHaveAtLeastSize 3
        events.filterIsInstance<WarnEvent>().first().message shouldContain "Error"
        events.filterIsInstance<AgentFinishedEvent>().firstOrNull() shouldNotBe null
    }

    "should convert other agents to user messages" {
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val otherAgentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        every { mockStreamSpec.content() } returns Flux.just("Response considering ", "other agent's input")

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(
            listOf(
                userMessage(projectId, caseId, "Question"),
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor(otherAgentId.toString(), "OtherAgent", ActorRole.AGENT),
                    content = listOf(MessageContent.Text("Other agent's response")),
                ),
                userMessage(projectId, caseId, "Follow-up"),
            ),
        ).toList()

        events shouldHaveAtLeastSize 4
        events.last() as? AgentFinishedEvent shouldNotBe null
    }

    // -------------------------------------------------------------------------
    // shouldContinue contract
    // -------------------------------------------------------------------------

    "should emit AgentFinishedEvent immediately and skip the LLM call when shouldContinue is false on entry" {
        // Verifies the pre-call guard in AgentSimple: when shouldContinue() is already
        // false before the LLM streaming call starts, the agent must bail out without
        // touching the ChatClient and still emit AgentFinishedEvent so the runtime
        // loop can terminate cleanly.
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(
            listOf(userMessage(projectId, caseId, "hello")),
            shouldContinue = { false },
        ).toList()

        // Must terminate cleanly
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
        // ThinkingEvent is emitted only after the guard — it must not appear
        events.filterIsInstance<ThinkingEvent>().size shouldBe 0
        // The LLM must never have been called
        verify(exactly = 0) { mockChatClient.prompt(any<Prompt>()) }
    }

    "should stop collecting chunks and still emit AgentFinishedEvent when shouldContinue flips to false mid-stream" {
        // Verifies the mid-stream guard: takeWhile { shouldContinue() } cancels the
        // upstream reactive stream as soon as the flag flips. Chunks produced after
        // the flip must not appear in the event list.
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        // Allow the first 2 takeWhile evaluations (i.e. the first 2 chunks pass through),
        // then return false so the stream is cancelled.
        var calls = 0
        val shouldContinue: () -> Boolean = { calls++ < 2 }

        val mockChatClient = mockk<ChatClient>(relaxed = true)
        val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
        every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
        // Provide 5 chunks — only the first 2 should ever be collected
        every { mockStreamSpec.content() } returns Flux.just("A ", "B ", "C ", "D ", "E")

        val agent = makeAgent(agentId, mockChatClient)

        val events = agent.run(
            listOf(userMessage(projectId, caseId, "hello")),
            shouldContinue = shouldContinue,
        ).toList()

        val chunks = events.filterIsInstance<TextChunkEvent>()
        // At most 2 chunks were emitted before the stream was cancelled
        (chunks.size <= 2) shouldBe true
        // Later chunks must not have leaked through
        chunks.none { it.chunk.contains("C") || it.chunk.contains("D") || it.chunk.contains("E") } shouldBe true
        // AgentFinishedEvent must still be emitted so the runtime can exit
        events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
    }
})
