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
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.UUID

class AgentAdvancedTest :
    StringSpec({
        timeout = 5000

        /**
         * Stub ChatModel.stream() to return responses in order.
         * [responses] are returned sequentially; the last is repeated if exhausted.
         */
        fun stubStreamResponses(
            mockChatModel: ChatModel,
            vararg responses: String,
        ) {
            var callIndex = 0
            every { mockChatModel.stream(any<Prompt>()) } answers {
                val text = responses.getOrElse(callIndex) { responses.last() }
                callIndex++
                Flux.just(ChatResponse(listOf(Generation(AssistantMessage(text)))))
            }
        }

        fun makeAgent(
            agentId: UUID,
            chatModel: ChatModel,
            tools: List<StandardTool<*>> = emptyList(),
            maxIterations: Int = 5,
        ): AgentAdvanced {
            val model =
                AiModel(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    description = "Test agent for advanced orchestration",
                    modelName = "gpt-4o",
                    providerName = "openai",
                )
            return AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                model = model,
                chatModel = chatModel,
                tools = tools,
                maxIterations = maxIterations,
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

        "should complete full orchestration loop with Answer tool" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatModel = mockk<ChatModel>(relaxed = true)
            stubStreamResponses(
                mockChatModel,
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

            val agent = makeAgent(agentId, mockChatModel, tools = listOf(answerTool))

            val events = agent.run(listOf(userMessage(namespaceId, caseId, "Hello, can you help me?"))).toList()

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

        "should emit WarnEvent and finish on LLM error" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatModel = mockk<ChatModel>(relaxed = true)
            every { mockChatModel.stream(any<Prompt>()) } returns Flux.error(RuntimeException("LLM unavailable"))

            val agent = makeAgent(agentId, mockChatModel)

            val events = agent.run(listOf(userMessage(namespaceId, caseId, "hello"))).toList()

            events.filterIsInstance<WarnEvent>().first().message shouldContain "Error"
        }
    })
