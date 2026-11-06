package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.chat.UsageTrackingChatModel
import io.whozoss.agentos.compaction.ContextBudget
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AgentSimpleToolRoundLimitSpec :
    StringSpec({
        timeout = 10_000

        val tool =
            object : StandardTool<Nothing> {
                override val name = "testTool"
                override val description = "Does nothing"
                override val inputSchema = """{"type":"object","properties":{}}"""
                override val version = "1"
                override val paramType: Class<Nothing>? = null

                override suspend fun execute(
                    input: Nothing?,
                    context: ToolContext,
                ) = ToolExecutionResult.success("done")
            }

        fun answer(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))

        fun toolCall(id: Int) =
            ChatResponse(
                listOf(
                    Generation(
                        AssistantMessage
                            .builder()
                            .content("")
                            .toolCalls(listOf(AssistantMessage.ToolCall("call-$id", "function", tool.name, "{}")))
                            .build(),
                    ),
                ),
            )

        /** Runs AgentSimple over the tracked model, the one the tool round limit lives in. */
        suspend fun run(reply: (Prompt, Int) -> ChatResponse): List<CaseEvent> {
            val requests = AtomicInteger()
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } answers { Flux.just(reply(firstArg(), requests.getAndIncrement())) }
            val model = AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test")
            val tracked = UsageTrackingChatModel(raw, UsageAccumulator(), AiApiType.OpenAI, model, maxToolRounds = 2)
            val agent =
                AgentSimple(
                    name = "test",
                    chatClient = ChatClient.builder(tracked).build(),
                    tools = listOf(tool),
                    llmProvider = "test",
                    llmModel = "test",
                )
            val input =
                MessageEvent(
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    actor = Actor("user", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("Run the tools")),
                )
            return agent.run(listOf(input)) { true }.toList()
        }

        "at the tool round limit the agent answers with the model's summary and reports the limit" {
            val events =
                run { prompt, request ->
                    val messages = prompt.instructions
                    val atLimit = messages.last() is UserMessage && messages.getOrNull(messages.size - 2) is ToolResponseMessage
                    if (atLimit) answer("Two tools ran, one step remains.") else toolCall(request)
                }

            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 2
            events.filterIsInstance<WarnEvent>().single().message shouldContain "maximum iterations (2)"
            val message = events.filterIsInstance<MessageEvent>().single()
            (message.content.single() as MessageContent.Text).content shouldBe "Two tools ran, one step remains."
            (events.last() as AgentFinishedEvent).maxIterationsReached shouldBe 2
        }

        "a run that answers right after its last allowed tool round is not flagged" {
            val events = run { _, request -> if (request < 2) toolCall(request) else answer("Finished.") }

            events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 2
            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
            (events.filterIsInstance<MessageEvent>().single().content.single() as MessageContent.Text).content shouldBe "Finished."
            (events.last() as AgentFinishedEvent).maxIterationsReached shouldBe null
        }

        // The limit used to exist only with usage tracking on: with it off, Spring AI's own loop ran the tools
        // and nothing bounded them. These go through the provider, as AgentServiceImpl does for an agent run.
        listOf(false, true).forEach { usageEnabled ->
            "usage ${if (usageEnabled) "enabled" else "disabled"}: the tool round limit applies to an AgentSimple built by the provider" {
                val requests = AtomicInteger()
                val raw = mockk<ChatModel>()
                every { raw.stream(any<Prompt>()) } answers {
                    val messages = firstArg<Prompt>().instructions
                    val atLimit = messages.last() is UserMessage && messages.getOrNull(messages.size - 2) is ToolResponseMessage
                    val index = requests.getAndIncrement()
                    Flux.just(if (atLimit) answer("Two tools ran, one step remains.") else toolCall(index))
                }
                val factory =
                    mockk<ChatModelFactory> {
                        every { createChatModel(any(), any(), any(), any(), any(), any(), any(), any()) } returns raw
                    }
                val aiModel = AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test", contextWindow = 1_000_000L)
                val aiProvider = AiProvider(namespaceId = UUID.randomUUID(), name = "test", apiType = AiApiType.OpenAI)
                val usage = if (usageEnabled) UsageAccumulator() else null
                val chatClient =
                    ChatClientProvider(factory, usageConfig = UsageConfigProperties(enabled = usageEnabled))
                        .getChatClient(
                            aiModel,
                            aiProvider,
                            accumulator = usage,
                            maxToolRounds = 2,
                            contextBudget = ContextBudget(contextWindow = aiModel.contextWindow),
                        )
                val agent = AgentSimple(name = "test", chatClient = chatClient, tools = listOf(tool), llmProvider = "test", llmModel = "test")
                val input =
                    MessageEvent(
                        namespaceId = UUID.randomUUID(),
                        caseId = UUID.randomUUID(),
                        actor = Actor("user", "User", ActorRole.USER),
                        content = listOf(MessageContent.Text("Run the tools")),
                    )

                val events = agent.run(listOf(input)) { true }.toList()

                events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 2
                events.filterIsInstance<WarnEvent>().single().message shouldContain "maximum iterations (2)"
                (events.filterIsInstance<MessageEvent>().single().content.single() as MessageContent.Text).content shouldBe
                    "Two tools ran, one step remains."
                (events.last() as AgentFinishedEvent).maxIterationsReached shouldBe 2
                // Rounds 0..2 each ask for a tool (the last one is not executed), then the summary request.
                requests.get() shouldBe 4
                // Accounting follows the switch only.
                (usage?.hasData ?: false) shouldBe false
            }
        }
    })
