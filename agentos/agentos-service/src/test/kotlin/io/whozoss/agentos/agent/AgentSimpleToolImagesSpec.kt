package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tool that produced images ends the current Spring AI call and the run continues with a rebuilt
 * conversation, so the very next LLM call carries the pixels (as a UserMessage with Media).
 *
 * These specs run the real Spring AI tool loop (ChatClient over [UsageTrackingChatModel]) over a
 * scripted provider, to cover how the signal travels out of the stream.
 */
class AgentSimpleToolImagesSpec :
    StringSpec({
        timeout = 15_000

        val image = MessageContent.Image(content = "aGVsbG8=", mimeType = "image/jpeg", width = 10, height = 10)

        fun toolNamed(
            toolName: String,
            result: () -> ToolExecutionResult,
        ) = object : StandardTool<Nothing> {
            override val name = toolName
            override val description = "Test tool"
            override val inputSchema = """{"type":"object","properties":{}}"""
            override val version = "1"
            override val paramType: Class<Nothing>? = null

            override suspend fun execute(
                input: Nothing?,
                context: ToolContext,
            ) = result()
        }

        val imageTool = toolNamed("FILES__readAsImage") { ToolExecutionResult.successWithImages("Loaded image x (attached).", listOf(image)) }
        val textTool = toolNamed("FILES__readFile") { ToolExecutionResult.success("plain text") }

        fun answer(text: String) = ChatResponse(listOf(Generation(AssistantMessage(text))))

        fun toolCalls(
            index: Int,
            toolName: String,
            text: String = "",
            count: Int = 1,
        ) = ChatResponse(
            listOf(
                Generation(
                    AssistantMessage
                        .builder()
                        .content(text)
                        .toolCalls((0 until count).map { AssistantMessage.ToolCall("call-$index-$it", "function", toolName, "{}") })
                        .build(),
                ),
            ),
        )

        class Run(
            val events: List<CaseEvent>,
            /** The prompt of every provider request, in order. */
            val prompts: List<List<Message>>,
            val usage: UsageAccumulator,
        )

        suspend fun run(
            tool: StandardTool<*>,
            reply: (index: Int) -> ChatResponse,
        ): Run {
            val prompts = CopyOnWriteArrayList<List<Message>>()
            val requests = AtomicInteger()
            val raw = mockk<ChatModel>()
            every { raw.defaultOptions } returns ToolCallingChatOptions.builder().build()
            every { raw.stream(any<Prompt>()) } answers {
                prompts.add(firstArg<Prompt>().instructions)
                Flux.just(reply(requests.getAndIncrement()))
            }
            val model = AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "test")
            val usage = UsageAccumulator()
            val tracked = UsageTrackingChatModel(raw, usage, AiApiType.OpenAI, model, maxToolRounds = 30)
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
                    content = listOf(MessageContent.Text("Look at my screenshot")),
                )
            return Run(agent.run(listOf(input)) { true }.toList(), prompts, usage)
        }

        fun List<Message>.mediaMessages() = filterIsInstance<UserMessage>().filter { it.media.isNotEmpty() }

        fun List<CaseEvent>.agentTexts() =
            filterIsInstance<MessageEvent>()
                .filter { it.actor.role == ActorRole.AGENT }
                .map { (it.content.single() as MessageContent.Text).content }

        "a tool returning images makes the next LLM call carry them, and the final answer comes from that call" {
            val result =
                run(imageTool) { index ->
                    if (index == 0) toolCalls(index, imageTool.name) else answer("I can see a cat.")
                }

            // Two provider requests: the one that asked for the tool, and the rebuilt continuation.
            result.prompts shouldHaveSize 2
            result.prompts[0].mediaMessages() shouldHaveSize 0

            // The continuation is well-formed: the tool exchange, then the pixels as the last message.
            val second = result.prompts[1]
            val assistantCall = second.filterIsInstance<AssistantMessage>().single { it.hasToolCalls() }
            assistantCall.toolCalls.single().name() shouldBe imageTool.name
            val toolResponse = second.filterIsInstance<ToolResponseMessage>().single()
            toolResponse.responses.single().id() shouldBe assistantCall.toolCalls.single().id()
            val media = second.mediaMessages().single()
            media.media shouldHaveSize 1
            (second.indexOf(media) > second.indexOf(toolResponse)) shouldBe true
            second.last() shouldBe media

            // Events: one request/response pair (images kept), one answer, one finish.
            val request = result.events.filterIsInstance<ToolRequestEvent>().single()
            val response = result.events.filterIsInstance<ToolResponseEvent>().single()
            response.toolRequestId shouldBe request.toolRequestId
            response.images shouldBe listOf(image)
            result.events.agentTexts() shouldBe listOf("I can see a cat.")
            result.events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            result.events.filterIsInstance<WarnEvent>() shouldHaveSize 0
            // The signal that ends the Spring AI call is not a provider failure: the run stays COMPLETED.
            result.usage.failed shouldBe false
            (result.events.indexOf(request) < result.events.indexOf(response)) shouldBe true
            (result.events.indexOf(response) < result.events.indexOf(result.events.filterIsInstance<MessageEvent>().single())) shouldBe true
        }

        "text streamed before the image tool call becomes its own agent message, and the answer another" {
            val result =
                run(imageTool) { index ->
                    if (index == 0) toolCalls(index, imageTool.name, text = "Let me look. ") else answer("A cat.")
                }

            result.events.agentTexts() shouldBe listOf("Let me look. ", "A cat.")
            result.events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            // The continuation does not end on the assistant's own words: the pixels come last.
            result.prompts[1].last() shouldBe result.prompts[1].mediaMessages().single()
        }

        "sibling tool calls Spring AI had not executed yet leave no dangling call in the rebuilt history" {
            // One assistant round asks for the same image tool twice: the first call ends the Spring AI
            // call, the second never runs and has no event, so it must not appear in the next prompt.
            val result =
                run(imageTool) { index ->
                    if (index == 0) toolCalls(index, imageTool.name, count = 2) else answer("Done.")
                }

            result.events.filterIsInstance<ToolRequestEvent>() shouldHaveSize 1
            val second = result.prompts[1]
            second.filterIsInstance<AssistantMessage>().single { it.hasToolCalls() }.toolCalls shouldHaveSize 1
            second.filterIsInstance<ToolResponseMessage>().single().responses shouldHaveSize 1
            result.events.agentTexts() shouldBe listOf("Done.")
        }

        "a text-only tool keeps the single streamed call, without any rebuilt history" {
            val chatClient = mockk<ChatClient>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            val toolCallbacks = slot<List<ToolCallback>>()
            val returned = mutableListOf<String>()
            every { chatClient.prompt(any<Prompt>()).toolCallbacks(capture(toolCallbacks)).stream() } answers {
                returned += toolCallbacks.captured.single().call("{}")
                streamSpec
            }
            every { streamSpec.chatResponse() } returns chatResponseFlux("The file says plain text.")
            val agent =
                AgentSimple(name = "test", chatClient = chatClient, tools = listOf(textTool), llmProvider = "test", llmModel = "test")
            val input =
                MessageEvent(
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    actor = Actor("user", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("Read the file")),
                )

            val events = agent.run(listOf(input)) { true }.toList()

            returned shouldBe listOf("plain text")
            verify(exactly = 1) { chatClient.prompt(any<Prompt>()) }
            events.filterIsInstance<ToolResponseEvent>().single().images shouldHaveSize 0
            events.agentTexts() shouldBe listOf("The file says plain text.")
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "a text-only tool in the real tool loop never restarts the call: one Spring AI loop, no media" {
            val result =
                run(textTool) { index ->
                    if (index == 0) toolCalls(index, textTool.name) else answer("Done.")
                }

            result.prompts shouldHaveSize 2
            result.prompts.forEach { it.mediaMessages() shouldHaveSize 0 }
            result.events.agentTexts() shouldBe listOf("Done.")
            result.events.filterIsInstance<ToolResponseEvent>() shouldHaveSize 1
        }

        "image continuations are capped per run, then the tool goes on with its text like before" {
            val cap = AgentSimple.MAX_IMAGE_CONTINUATIONS
            // Every request asks for the image tool until the cap is spent: cap restarts, then one last
            // call whose tool no longer ends the Spring AI call, then the answer.
            val result =
                run(imageTool) { index ->
                    if (index <= cap) toolCalls(index, imageTool.name) else answer("Enough pictures.")
                }

            result.events.filterIsInstance<ToolResponseEvent>() shouldHaveSize cap + 1
            result.prompts shouldHaveSize cap + 2
            // Each restart carries one more picture than the previous one.
            for (restart in 1..cap) {
                result.prompts[restart].mediaMessages() shouldHaveSize restart
            }
            // Past the cap the images are not attached within the run (they will be on the next turn).
            result.prompts[cap + 1].mediaMessages() shouldHaveSize cap
            result.events.agentTexts() shouldBe listOf("Enough pictures.")
            result.events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
            result.events.filterIsInstance<WarnEvent>() shouldHaveSize 0
        }

        "a stopped run does not restart the call: the image tool goes on with its text" {
            // shouldContinue is true at the start of the run, then false by the time the tool is called.
            val checks = AtomicInteger()
            val chatClient = mockk<ChatClient>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            val toolCallbacks = slot<List<ToolCallback>>()
            var returned: String? = null
            var thrown: Throwable? = null
            every { chatClient.prompt(any<Prompt>()).toolCallbacks(capture(toolCallbacks)).stream() } answers {
                thrown = runCatching { returned = toolCallbacks.captured.single().call("{}") }.exceptionOrNull()
                streamSpec
            }
            every { streamSpec.chatResponse() } returns Flux.empty()
            val agent =
                AgentSimple(name = "test", chatClient = chatClient, tools = listOf(imageTool), llmProvider = "test", llmModel = "test")
            val input =
                MessageEvent(
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    actor = Actor("user", "User", ActorRole.USER),
                    content = listOf(MessageContent.Text("Look")),
                )

            val events = agent.run(listOf(input)) { checks.getAndIncrement() == 0 }.toList()

            thrown shouldBe null
            returned shouldBe "Loaded image x (attached)."
            verify(exactly = 1) { chatClient.prompt(any<Prompt>()) }
            events.filterIsInstance<ToolResponseEvent>().single().images shouldBe listOf(image)
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }
    })
