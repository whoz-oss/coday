package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.chat.UsageTrackingChatModel
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** [AgentSimple.onFinalRequest] reports the last request of a turn exactly as the provider received it. */
class AgentSimpleFinalRequestSpec :
    StringSpec({
        timeout = 10_000

        fun tool(name: String) =
            object : StandardTool<Nothing> {
                override val name = name
                override val description = "The $name tool"
                override val inputSchema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
                override val version = "1"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ) = ToolExecutionResult.success("$name output")
            }

        val tools = listOf(tool("lookup"), tool("search"))

        fun answer(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))

        fun toolCall() =
            ChatResponse(
                listOf(
                    Generation(
                        AssistantMessage
                            .builder()
                            .content("Let me look.")
                            .toolCalls(listOf(AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
                            .build(),
                    ),
                ),
            )

        val input =
            MessageEvent(
                namespaceId = UUID.randomUUID(),
                caseId = UUID.randomUUID(),
                actor = Actor("user", "User", ActorRole.USER),
                content = listOf(MessageContent.Text("What is the status?")),
            )

        /** What the provider received, and what the agent reported. */
        class Run(
            val sent: List<Prompt>,
            val reported: List<FinalLlmRequest>,
            val events: List<CaseEvent>,
        )

        suspend fun run(
            tracked: Boolean,
            shouldContinue: () -> Boolean = { true },
            reply: (Int) -> ChatResponse,
        ): Run {
            val sent = CopyOnWriteArrayList<Prompt>()
            val requests = AtomicInteger()
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } answers {
                sent.add(firstArg())
                Flux.just(reply(requests.getAndIncrement()))
            }
            val lastSent = AtomicReference<List<Message>?>(null)
            val model =
                if (tracked) {
                    UsageTrackingChatModel(
                        raw,
                        UsageAccumulator(),
                        AiApiType.OpenAI,
                        AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test"),
                        onRequest = { lastSent.set(it.instructions.toList()) },
                    )
                } else {
                    raw
                }
            val reported = CopyOnWriteArrayList<FinalLlmRequest>()
            val agent =
                AgentSimple(
                    name = "test",
                    chatClient = ChatClient.builder(model).build(),
                    tools = tools,
                    systemPrompt = "Namespace context",
                    instructions = "Be helpful.",
                    llmProvider = "test",
                    llmModel = "test",
                    lastSentMessages = lastSent::get,
                    onFinalRequest = { reported.add(it) },
                )
            val events = agent.run(listOf(input), shouldContinue).toList()
            return Run(sent, reported, events)
        }

        "reports the exact messages and tool definitions of the last request, tool rounds included, and the answer" {
            val result = run(tracked = true) { request -> if (request == 0) toolCall() else answer("All green.") }

            result.sent.size shouldBe 2
            val reported = result.reported.single()
            reported.messages shouldBe result.sent.last().instructions
            reported.messages.first().shouldBeInstanceOf<SystemMessage>().text shouldBe "Namespace context\nBe helpful."
            reported.messages.map { it.messageType.name } shouldBe listOf("SYSTEM", "USER", "ASSISTANT", "TOOL")
            val sentDefinitions = (result.sent.last().options as ToolCallingChatOptions).toolCallbacks.map { it.toolDefinition }
            reported.toolDefinitions shouldBe sentDefinitions
            reported.toolDefinitions.map(ToolDefinition::name) shouldBe listOf("lookup", "search")
            // The answer as recorded in the case: what the model said over the whole turn.
            val recorded = result.events.filterIsInstance<MessageEvent>().single().content.single() as MessageContent.Text
            reported.finalText shouldBe recorded.content
            reported.finalText shouldBe "Let me look.All green."
            reported.caseId shouldBe input.caseId
            reported.coveredEventCount shouldBe 1
            reported.lastCoveredEventId shouldBe input.id
            result.events.last().shouldBeInstanceOf<AgentFinishedEvent>()
        }

        "without a tracked model, reports the messages of the last call, which is the request when no tool ran" {
            val result = run(tracked = false) { answer("All green.") }

            result.sent.size shouldBe 1
            result.reported.single().messages shouldBe result.sent.single().instructions
        }

        "reports nothing when the turn was stopped" {
            val result = run(tracked = true, shouldContinue = { false }) { answer("All green.") }

            result.reported.shouldBeEmpty()
        }

        "a failing callback does not affect the turn" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.just(answer("All green."))
            val agent =
                AgentSimple(
                    name = "test",
                    chatClient = ChatClient.builder(raw).build(),
                    tools = tools,
                    llmProvider = "test",
                    llmModel = "test",
                    onFinalRequest = { throw IllegalStateException("registry down") },
                )

            val events = agent.run(listOf(input)) { true }.toList()

            (events.filterIsInstance<MessageEvent>().single().content.single() as MessageContent.Text).content shouldBe "All green."
            events.last().shouldBeInstanceOf<AgentFinishedEvent>()
        }
    })
