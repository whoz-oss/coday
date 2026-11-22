package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.chat.LlmExchangeRecorder
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.tool.definition.ToolDefinition
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

        /** What the provider received, what the agent reported, and the events of the turn. */
        class Run(
            val sent: List<Prompt>,
            val reported: List<FinalLlmRequest>,
            val events: List<CaseEvent>,
        )

        suspend fun run(
            tracked: Boolean,
            shouldContinue: () -> Boolean = { true },
            onEvent: (CaseEvent) -> Unit = {},
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
            val recorder = LlmExchangeRecorder()
            val model =
                if (tracked) {
                    UsageTrackingChatModel(
                        raw,
                        UsageAccumulator(),
                        AiApiType.OpenAI,
                        AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test"),
                        exchangeRecorder = recorder,
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
                    lastExchange = { recorder.lastExchange },
                    onFinalRequest = { reported.add(it) },
                )
            val events = agent.run(listOf(input), shouldContinue).onEach(onEvent).toList()
            return Run(sent, reported, events)
        }

        "reports the exact messages and tool definitions of the last request, tool rounds included, and its answer" {
            val result = run(tracked = true) { request -> if (request == 0) toolCall() else answer("All green.") }

            result.sent.size shouldBe 2
            val reported = result.reported.single()
            reported.messages shouldBe result.sent.last().instructions
            reported.messages.first().shouldBeInstanceOf<SystemMessage>().text shouldBe "Namespace context\nBe helpful."
            reported.messages.map { it.messageType.name } shouldBe listOf("SYSTEM", "USER", "ASSISTANT", "TOOL")
            val sentDefinitions = (result.sent.last().options as ToolCallingChatOptions).toolCallbacks.map { it.toolDefinition }
            reported.toolDefinitions shouldBe sentDefinitions
            reported.toolDefinitions.map(ToolDefinition::name) shouldBe listOf("lookup", "search")
            // The answer to the last request only: "Let me look." is already in its messages.
            reported.finalText shouldBe "All green."
            reported.caseId shouldBe input.caseId
        }

        "reports the prompt size the provider reported for the last request" {
            val result =
                run(tracked = true) { request ->
                    val usage = DefaultUsage(1200 + request, 5, 1205 + request, OpenAiApi.Usage(5, 1200 + request, 1205 + request, null, null))
                    val response = if (request == 0) toolCall() else answer("All green.")
                    ChatResponse(response.results, ChatResponseMetadata.builder().usage(usage).build())
                }

            result.reported.single().promptTokens shouldBe 1201L
        }

        "covers the conversation up to the recorded answer, the events of the turn included" {
            val result = run(tracked = true) { request -> if (request == 0) toolCall() else answer("All green.") }

            val recordedAnswer = result.events.filterIsInstance<MessageEvent>().single()
            val reported = result.reported.single()
            reported.lastCoveredEventId shouldBe recordedAnswer.id
            // The question, the tool request and response, the answer.
            reported.coveredEventCount shouldBe (listOf(input) + result.events).count { it.isConversationEvent() }
            reported.coveredEventCount shouldBe 4
            result.events.last().shouldBeInstanceOf<AgentFinishedEvent>()
        }

        "reports nothing when the exact request is unknown (an untracked model)" {
            val result = run(tracked = false) { answer("All green.") }

            result.sent.size shouldBe 1
            result.reported.shouldBeEmpty()
        }

        "reports nothing when the turn was stopped once its stream ended" {
            // Stopped right as the answer is recorded: emit returns once the event is collected.
            val stopped = AtomicBoolean(false)
            val result =
                run(tracked = true, shouldContinue = { !stopped.get() }, onEvent = { if (it is MessageEvent) stopped.set(true) }) {
                    answer("All green.")
                }

            // The turn went as far as recording its answer, but was stopped meanwhile.
            result.events.filterIsInstance<MessageEvent>().single()
            result.reported.shouldBeEmpty()
        }

        "a failing callback does not affect the turn" {
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } returns Flux.just(answer("All green."))
            val recorder = LlmExchangeRecorder()
            val tracked =
                UsageTrackingChatModel(
                    raw,
                    UsageAccumulator(),
                    AiApiType.OpenAI,
                    AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test"),
                    exchangeRecorder = recorder,
                )
            val agent =
                AgentSimple(
                    name = "test",
                    chatClient = ChatClient.builder(tracked).build(),
                    tools = tools,
                    llmProvider = "test",
                    llmModel = "test",
                    lastExchange = { recorder.lastExchange },
                    onFinalRequest = { throw IllegalStateException("registry down") },
                )

            val events = agent.run(listOf(input)) { true }.toList()

            (events.filterIsInstance<MessageEvent>().single().content.single() as MessageContent.Text).content shouldBe "All green."
            events.last().shouldBeInstanceOf<AgentFinishedEvent>()
        }
    })
