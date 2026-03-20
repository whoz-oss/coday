package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
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

/**
 * Stub stream().content() for the no-tool path (AgentSimple uses stream() when
 * no tools are registered, for progressive text display).
 */
fun stubChatResponseStream(
    mockChatClient: ChatClient,
    vararg chunks: String,
) {
    val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
    every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
    every { mockStreamSpec.content() } returns Flux.fromIterable(chunks.toList())
}

class AgentSimpleTest :
    StringSpec({
        timeout = 5000

        fun makeAgent(
            agentId: UUID,
            chatClient: ChatClient,
            name: String = "SimpleAgent",
            tools: Collection<StandardTool<*>> = emptyList(),
            instructions: String? = null,
        ): AgentSimple {
            val model =
                AiModel(
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

        fun userMessage(
            namespaceId: UUID,
            caseId: UUID,
            text: String,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor("user1", "User One", ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
        )

        "should complete with single LLM call" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            stubChatResponseStream(mockChatClient, "Hello! ", "I can help you ", "with that.")

            val agent = makeAgent(agentId, mockChatClient, instructions = "You are a helpful assistant.")

            val events = agent.run(listOf(userMessage(namespaceId, caseId, "Hello, can you help me?"))).toList()

            events shouldHaveAtLeastSize 4
            events[0] as? ThinkingEvent shouldNotBe null
            events.filterIsInstance<TextChunkEvent>().size shouldBe 3

            val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
            messageEvent shouldNotBe null
            messageEvent!!.actor.role shouldBe ActorRole.AGENT
            messageEvent.actor.id shouldBe agentId.toString()
            messageEvent.actor.displayName shouldBe "SimpleAgent"
            messageEvent.content
                .filterIsInstance<MessageContent.Text>()
                .first()
                .content shouldContain "Hello"

            val finishedEvent = events.last() as? AgentFinishedEvent
            finishedEvent shouldNotBe null
            finishedEvent!!.agentId shouldBe agentId
            finishedEvent.agentName shouldBe "SimpleAgent"
        }

        "should handle conversation with multiple messages" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            stubChatResponseStream(mockChatClient, "Based on our ", "previous conversation, ", "here's my response.")

            val agent = makeAgent(agentId, mockChatClient)

            val events =
                agent
                    .run(
                        listOf(
                            userMessage(namespaceId, caseId, "First question"),
                            MessageEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                actor = Actor(agentId.toString(), "SimpleAgent", ActorRole.AGENT),
                                content = listOf(MessageContent.Text("First answer")),
                            ),
                            userMessage(namespaceId, caseId, "Follow-up question"),
                        ),
                    ).toList()

            events shouldHaveAtLeastSize 4
            events
                .filterIsInstance<MessageEvent>()
                .first()
                .content
                .filterIsInstance<MessageContent.Text>()
                .first()
                .content shouldContain "previous"
        }

        "should handle error gracefully" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.error(RuntimeException("API Error"))

            val agent = makeAgent(agentId, mockChatClient)

            val events =
                agent.run(listOf(userMessage(namespaceId = namespaceId, caseId = caseId, text = "Hello"))).toList()

            events shouldHaveAtLeastSize 3
            events.filterIsInstance<WarnEvent>().first().message shouldContain "Error"
            events.filterIsInstance<AgentFinishedEvent>().firstOrNull() shouldNotBe null
        }

        "should convert other agents to user messages" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val otherAgentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            stubChatResponseStream(mockChatClient, "Response considering ", "other agent's input")

            val agent = makeAgent(agentId, mockChatClient)

            val events =
                agent
                    .run(
                        listOf(
                            userMessage(namespaceId, caseId, "Question"),
                            MessageEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                actor = Actor(otherAgentId.toString(), "OtherAgent", ActorRole.AGENT),
                                content = listOf(MessageContent.Text("Other agent's response")),
                            ),
                            userMessage(namespaceId, caseId, "Follow-up"),
                        ),
                    ).toList()

            events shouldHaveAtLeastSize 4
            events.last() as? AgentFinishedEvent shouldNotBe null
        }
    })
