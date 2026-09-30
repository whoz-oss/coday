package io.whozoss.agentos.agent

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.chat.UsageTrackingChatModel
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.usage.RunCostService
import io.whozoss.agentos.usage.UsageRecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.api.OpenAiApi
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class AgentSimpleCostStopSpec :
    StringSpec({
        for (stop in listOf(true, false)) {
            "${if (stop) "stopping" else "continuing"} a cost pause preserves completed tool events without replay" {
                coroutineScope {
                    var case = Case(namespaceId = UUID.randomUUID(), runCostThreshold = 10.0)
                    val cases = mockk<CaseRepository>()
                    every { cases.findById(case.id) } answers { case }
                    every { cases.save(any()) } answers { firstArg<Case>().also { case = it } }
                    val caseEvents = mockk<CaseEventService>()
                    every { caseEvents.findByParent(case.id) } returns emptyList()
                    val records = mockk<UsageRecordService>()
                    every { records.sumCostByCaseTreeSince(any(), any()) } returns null
                    val costs = RunCostService(cases, caseEvents, mockk<NamespaceService>(), records)
                    val usage = UsageAccumulator()
                    val registration = costs.register(case.id, usage)
                    val calls = AtomicInteger()
                    val executions = AtomicInteger()
                    val tool =
                        object : StandardTool<Nothing> {
                            override val name = "testTool"
                            override val description = "Count tool executions"
                            override val inputSchema = """{"type":"object","properties":{}}"""
                            override val version = "1"
                            override val paramType: Class<Nothing>? = null

                            override suspend fun execute(
                                input: Nothing?,
                                context: ToolContext,
                            ): ToolExecutionResult {
                                executions.incrementAndGet()
                                return ToolExecutionResult.success("done")
                            }
                        }
                    val raw = mockk<ChatModel>()
                    every { raw.stream(any<Prompt>()) } answers {
                        val round = calls.incrementAndGet()
                        val tokens = if (round == 2) 10 else 1
                        val message =
                            AssistantMessage
                                .builder()
                                .content(if (round <= 2) "" else "Done")
                                .toolCalls(
                                    if (round <= 2) {
                                        listOf(AssistantMessage.ToolCall("call-$round", "function", tool.name, "{}"))
                                    } else {
                                        emptyList()
                                    },
                                ).build()
                        Flux.just(
                            ChatResponse(
                                listOf(Generation(message)),
                                ChatResponseMetadata
                                    .builder()
                                    .usage(DefaultUsage(tokens, 0, tokens, OpenAiApi.Usage(0, tokens, tokens, null, null)))
                                    .build(),
                            ),
                        )
                    }
                    val model =
                        AiModel(
                            aiProviderId = UUID.randomUUID(),
                            apiModelName = "test",
                            pricing = ModelPricing(inputMTokens = 1_000_000.0),
                        )
                    val agent =
                        AgentSimple(
                            name = "test",
                            chatClient = ChatClient.builder(UsageTrackingChatModel(raw, usage, AiApiType.OpenAI, model)).build(),
                            tools = listOf(tool),
                            llmProvider = "test",
                            llmModel = "test",
                        )
                    val input =
                        MessageEvent(
                            namespaceId = case.namespaceId,
                            caseId = case.id,
                            actor = Actor("user", "User", ActorRole.USER),
                            content = listOf(MessageContent.Text("Run the tools")),
                        )
                    val run = async(Dispatchers.IO) { agent.run(listOf(input)) { true }.toList() }
                    try {
                        eventually(5.seconds) { costs.state(case.id).paused shouldBe true }
                        executions.get() shouldBe 1
                        calls.get() shouldBe 2
                        if (stop) costs.stop(case.id) else costs.continueRun(case.id, 10.0)
                        val events = withTimeout(5.seconds) { run.await() }
                        val expectedTools = if (stop) 1 else 2
                        executions.get() shouldBe expectedTools
                        calls.get() shouldBe if (stop) 2 else 3
                        val requests = events.filterIsInstance<ToolRequestEvent>()
                        val responses = events.filterIsInstance<ToolResponseEvent>()
                        requests.size shouldBe expectedTools
                        responses.map { it.toolRequestId } shouldBe requests.map { it.toolRequestId }
                        requests.zip(responses).forEach { (request, response) ->
                            (events.indexOf(request) < events.indexOf(response)) shouldBe true
                        }
                        events.last()::class shouldBe AgentFinishedEvent::class
                        usage.failed shouldBe false
                    } finally {
                        costs.stop(case.id)
                        run.cancelAndJoin()
                        registration.finish {}
                    }
                }
            }
        }
    })
