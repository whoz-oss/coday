package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.kotest.matchers.collections.shouldHaveSize
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.UUID

class AgentSimpleUnitSpec :
    StringSpec({
        timeout = 5000

        fun makeAgent(
            agentId: UUID,
            chatClient: ChatClient,
            name: String = "SimpleAgent",
            tools: Collection<StandardTool<*>> = emptyList(),
            instructions: String? = null,
            reasoningEffort: ReasoningEffort? = null,
            requestedReasoningEffort: ReasoningEffort? = null,
            toolHistoryReplay: ToolHistoryReplay = ToolHistoryReplay.FULL,
        ): AgentSimple =
            AgentSimple(
                metadata = EntityMetadata(id = agentId),
                name = name,
                chatClient = chatClient,
                tools = tools,
                instructions = instructions,
                llmProvider = "test-provider",
                llmModel = "test-model",
                reasoningEffort = reasoningEffort,
                requestedReasoningEffort = requestedReasoningEffort,
                toolHistoryReplay = toolHistoryReplay,
            )

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
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Hello! ", "I can help you ", "with that.")

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

        "the agent message carries the reasoning effort sent and the one asked" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("391")

            val withEffort =
                makeAgent(
                    UUID.randomUUID(),
                    mockChatClient,
                    reasoningEffort = ReasoningEffort.HIGH,
                    requestedReasoningEffort = ReasoningEffort.XHIGH,
                )
            val withoutEffort = makeAgent(UUID.randomUUID(), mockChatClient)

            val withEffortMessage =
                withEffort.run(listOf(userMessage(namespaceId, caseId, "17*23?"))).toList()
                    .filterIsInstance<MessageEvent>().single()
            withEffortMessage.reasoningEffort shouldBe ReasoningEffort.HIGH
            withEffortMessage.requestedReasoningEffort shouldBe ReasoningEffort.XHIGH
            val withoutEffortMessage =
                withoutEffort.run(listOf(userMessage(namespaceId, caseId, "17*23?"))).toList()
                    .filterIsInstance<MessageEvent>().single()
            withoutEffortMessage.reasoningEffort shouldBe null
            withoutEffortMessage.requestedReasoningEffort shouldBe null
        }

        "should handle conversation with multiple messages" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns
                chatResponseFlux(
                    "Based on our ",
                    "previous conversation, ",
                    "here's my response.",
                )

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
            every { mockStreamSpec.chatResponse() } returns Flux.error(RuntimeException("API Error"))

            val agent = makeAgent(agentId, mockChatClient)

            val events =
                agent.run(listOf(userMessage(namespaceId = namespaceId, caseId = caseId, text = "Hello"))).toList()

            events shouldHaveAtLeastSize 3
            events.filterIsInstance<WarnEvent>().first().message shouldContain "Error"
            events.filterIsInstance<AgentFinishedEvent>().firstOrNull() shouldNotBe null
        }

        "should convert other agents to user messages with XML agent tag" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val otherAgentId = UUID.randomUUID()

            val capturedPrompt = slot<Prompt>()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(capture(capturedPrompt)).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Response considering ", "other agent's input")

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

            // The other agent's message must be wrapped with <agent=Name> XML tag
            // so the current agent's LLM knows who said what in a multi-agent conversation.
            val promptMessages = capturedPrompt.captured.instructions
            val userMessages = promptMessages.filterIsInstance<UserMessage>()
            val otherAgentMsg = userMessages.firstOrNull { it.text.contains("""<agent name="OtherAgent">""") }
            otherAgentMsg shouldNotBe null
            otherAgentMsg!!.text shouldContain "Other agent's response"
            otherAgentMsg.text shouldContain "</agent>"
        }

        "should strip conversation tags hallucinated by the LLM in its response" {
            // If the LLM mimics the context format and wraps its own response in <agent=...>
            // or <user name="..."> tags, those must be stripped from the stored MessageEvent.
            // TextChunkEvents are left as-is (tags split across chunks are invisible in markdown).
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            // LLM wraps its own answer in an agent tag
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("""<agent name="SimpleAgent">""", "Hello!", "</agent>")

            val agent = makeAgent(agentId, mockChatClient, name = "SimpleAgent")
            val events = agent.run(listOf(userMessage(namespaceId, caseId, "Hi"))).toList()

            val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
            messageEvent shouldNotBe null
            // Tags must be stripped from the stored message
            messageEvent!!
                .content
                .filterIsInstance<MessageContent.Text>()
                .first()
                .content shouldBe "Hello!"
        }

        "should wrap user messages with XML user tag" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val capturedPrompt = slot<Prompt>()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(capture(capturedPrompt)).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("Hello!")

            val agent = makeAgent(agentId, mockChatClient)

            agent.run(listOf(userMessage(namespaceId, caseId, "Hello, can you help me?"))).toList()

            val promptMessages = capturedPrompt.captured.instructions
            val userMessages = promptMessages.filterIsInstance<UserMessage>()
            userMessages shouldHaveAtLeastSize 1
            // User messages must be tagged with <user name="..."> so the LLM can distinguish
            // them from agent messages in multi-agent conversations.
            userMessages.first().text shouldContain "<user name=\"User One\">"
            userMessages.first().text shouldContain "Hello, can you help me?"
            userMessages.first().text shouldContain "</user>"
        }

        // -------------------------------------------------------------------------
        // shouldContinue contract
        // -------------------------------------------------------------------------

        "should emit AgentFinishedEvent immediately and skip the LLM call when shouldContinue is false on entry" {
            // Verifies the pre-call guard in AgentSimple: when shouldContinue() is already
            // false before the LLM streaming call starts, the agent must bail out without
            // touching the ChatClient and still emit AgentFinishedEvent so the runtime
            // loop can terminate cleanly.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val agent = makeAgent(agentId, mockChatClient)

            val events =
                agent
                    .run(
                        listOf(userMessage(namespaceId, caseId, "hello")),
                        shouldContinue = { false },
                    ).toList()

            // Must terminate cleanly
            events.filterIsInstance<AgentFinishedEvent>().size shouldBe 1
            // ThinkingEvent is emitted only after the guard — it must not appear
            events.filterIsInstance<ThinkingEvent>().size shouldBe 0
            // The LLM must never have been called
            verify(exactly = 0) { mockChatClient.prompt(any<Prompt>()) }
        }

        // -------------------------------------------------------------------------
        // sessionContext injection
        // -------------------------------------------------------------------------

        "session context on last user message is injected as UserMessage before that message" {
            // When the last user MessageEvent carries a non-null sessionContext, a synthetic
            // UserMessage with <session-context> XML must be prepended to it in the LLM prompt.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val capturedPrompt = slot<Prompt>()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(capture(capturedPrompt)).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("ok")

            val agent = makeAgent(agentId, mockChatClient)
            val msgWithContext = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user1", "User One", ActorRole.USER),
                content = listOf(MessageContent.Text("help me")),
                sessionContext = mapOf("pageType" to "project", "entityId" to "42"),
            )

            agent.run(listOf(msgWithContext)).toList()

            val promptMessages = capturedPrompt.captured.instructions
            val userMessages = promptMessages.filterIsInstance<UserMessage>()
            // Expect at least 2 UserMessages: the context injection + the actual message
            (userMessages.size >= 2) shouldBe true
            val contextMsg = userMessages.firstOrNull { it.text.contains("<session-context>") }
            contextMsg shouldNotBe null
            contextMsg!!.text shouldContain "pageType: project"
            contextMsg.text shouldContain "entityId: 42"
            contextMsg.text shouldContain "</session-context>"
            // The context message must immediately precede the user message
            val contextIndex = promptMessages.indexOf(contextMsg)
            val userMsgIndex = promptMessages.indexOfFirst {
                it is UserMessage && it.text.contains("<user name=\"User One\">") && it.text.contains("help me")
            }
            (contextIndex + 1) shouldBe userMsgIndex
        }

        "session context on earlier user messages is NOT injected into the LLM prompt" {
            // Only the last user message's sessionContext is injected.
            // A context on a previous turn must be silently ignored.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val capturedPrompt = slot<Prompt>()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(capture(capturedPrompt)).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("ok")

            val agent = makeAgent(agentId, mockChatClient)
            val firstMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user1", "User One", ActorRole.USER),
                content = listOf(MessageContent.Text("first")),
                sessionContext = mapOf("pageType" to "dashboard"),
            )
            val agentReply = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor(agentId.toString(), "SimpleAgent", ActorRole.AGENT),
                content = listOf(MessageContent.Text("got it")),
            )
            val secondMsg = MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user1", "User One", ActorRole.USER),
                content = listOf(MessageContent.Text("follow-up")),
                sessionContext = null,
            )

            agent.run(listOf(firstMsg, agentReply, secondMsg)).toList()

            val promptMessages = capturedPrompt.captured.instructions
            val contextMsg = promptMessages.filterIsInstance<UserMessage>()
                .firstOrNull { it.text.contains("<session-context>") }
            // No context injection: the last user message has no sessionContext
            contextMsg.shouldBeNull()
        }

        "no session context message is injected when last user message has null sessionContext" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val capturedPrompt = slot<Prompt>()
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { mockChatClient.prompt(capture(capturedPrompt)).stream() } returns mockStreamSpec
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("ok")

            val agent = makeAgent(agentId, mockChatClient)

            agent.run(listOf(userMessage(namespaceId, caseId, "hello"))).toList()

            val promptMessages = capturedPrompt.captured.instructions
            val contextMsg = promptMessages.filterIsInstance<UserMessage>()
                .firstOrNull { it.text.contains("<session-context>") }
            contextMsg.shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Tool history replay (past turns)
        // -------------------------------------------------------------------------

        /** Two turns: past exchanges r1 (with an image) and r2, then the current turn's r3. */
        fun twoTurnHistory(
            namespaceId: UUID,
            caseId: UUID,
        ): List<CaseEvent> {
            fun request(
                id: String,
                args: String,
            ) = ToolRequestEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = id,
                toolName = "TOOL__$id",
                args = args,
            )

            fun response(
                id: String,
                output: String,
                images: List<MessageContent.Image> = emptyList(),
            ) = ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                toolRequestId = id,
                toolName = "TOOL__$id",
                output = MessageContent.Text(output),
                images = images,
            )
            val image = MessageContent.Image(content = "aGVsbG8=", mimeType = "image/jpeg", width = 10, height = 10)
            return listOf(
                userMessage(namespaceId, caseId, "first"),
                request("r1", """{"a":1}"""),
                response("r1", "RESULT-1", listOf(image)),
                request("r2", """{"b":2}"""),
                response("r2", "RESULT-2"),
                userMessage(namespaceId, caseId, "second"),
                request("r3", """{"c":3}"""),
                response("r3", "RESULT-3"),
            )
        }

        fun List<Message>.assertWellFormed() {
            forEachIndexed { index, message ->
                if (message is AssistantMessage && message.hasToolCalls()) {
                    val next = getOrNull(index + 1)
                    (next is ToolResponseMessage) shouldBe true
                    (next as ToolResponseMessage).responses.map { it.id() } shouldBe message.toolCalls.map { it.id() }
                }
            }
            // no assistant message may be left empty (no text and no tool call)
            filterIsInstance<AssistantMessage>().none { it.text.isNullOrEmpty() && it.toolCalls.isEmpty() } shouldBe true
        }

        "FULL replays past tool calls, results and images unchanged" {
            val history = twoTurnHistory(UUID.randomUUID(), UUID.randomUUID())
            val agent = makeAgent(UUID.randomUUID(), mockk(relaxed = true))

            val messages = agent.convertEventsToMessages(history)

            messages.assertWellFormed()
            val results = messages.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }.map { it.responseData() }
            results shouldBe listOf("RESULT-1", "RESULT-2", "RESULT-3")
            messages.filterIsInstance<UserMessage>().count { it.media.isNotEmpty() } shouldBe 1
        }

        "CALLS_ONLY keeps past tool calls with their args, replaces their results and attaches no past image" {
            val history = twoTurnHistory(UUID.randomUUID(), UUID.randomUUID())
            val agent = makeAgent(UUID.randomUUID(), mockk(relaxed = true), toolHistoryReplay = ToolHistoryReplay.CALLS_ONLY)

            val messages = agent.convertEventsToMessages(history)

            messages.assertWellFormed()
            val calls = messages.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls }
            calls.map { it.id() } shouldBe listOf("r1", "r2", "r3")
            calls.map { it.arguments() } shouldBe listOf("""{"a":1}""", """{"b":2}""", """{"c":3}""")
            val results = messages.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }.map { it.responseData() }
            results shouldBe listOf(TOOL_RESULT_OMITTED, TOOL_RESULT_OMITTED, "RESULT-3")
            messages.filterIsInstance<UserMessage>().none { it.media.isNotEmpty() } shouldBe true
        }

        "NONE removes past tool calls and results, without leaving an empty AssistantMessage" {
            val history = twoTurnHistory(UUID.randomUUID(), UUID.randomUUID())
            val agent = makeAgent(UUID.randomUUID(), mockk(relaxed = true), toolHistoryReplay = ToolHistoryReplay.NONE)

            val messages = agent.convertEventsToMessages(history)

            messages.assertWellFormed()
            val calls = messages.filterIsInstance<AssistantMessage>().flatMap { it.toolCalls }
            calls.map { it.id() } shouldBe listOf("r3")
            messages.filterIsInstance<AssistantMessage>().all { it.toolCalls.isNotEmpty() } shouldBe true
            messages.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }.map { it.responseData() } shouldBe
                listOf("RESULT-3")
            messages.filterIsInstance<UserMessage>().none { it.media.isNotEmpty() } shouldBe true
            // both user messages are kept
            messages.filterIsInstance<UserMessage>() shouldHaveSize 2
        }

        "NONE and CALLS_ONLY replay the current turn in full, even when its exchanges were accumulated mid-run" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val history = twoTurnHistory(namespaceId, caseId)
            // the current turn grows: a second call of the same turn, as AgentSimple's runEvents would add
            val extra =
                listOf(
                    ToolRequestEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "r4",
                        toolName = "TOOL__r4",
                        args = """{"d":4}""",
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "r4",
                        toolName = "TOOL__r4",
                        output = MessageContent.Text("RESULT-4"),
                    ),
                )

            listOf(ToolHistoryReplay.CALLS_ONLY, ToolHistoryReplay.NONE).forEach { mode ->
                val agent = makeAgent(UUID.randomUUID(), mockk(relaxed = true), toolHistoryReplay = mode)

                val messages = agent.convertEventsToMessages(history + extra)

                messages.assertWellFormed()
                messages.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }.map { it.responseData() }.takeLast(2) shouldBe
                    listOf("RESULT-3", "RESULT-4")
            }
        }

        "an AnswerEvent does not make earlier tool results past" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()
            val question =
                QuestionEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "A", question = "Which?")
            val history =
                listOf(
                    userMessage(namespaceId, caseId, "go"),
                    ToolRequestEvent(namespaceId = namespaceId, caseId = caseId, toolRequestId = "r1", toolName = "TOOL__a", args = "{}"),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "r1",
                        toolName = "TOOL__a",
                        output = MessageContent.Text("RESULT-1"),
                    ),
                    question,
                    question.createAnswer(Actor("user1", "User One", ActorRole.USER), "this one"),
                )
            val agent = makeAgent(agentId, mockk(relaxed = true), toolHistoryReplay = ToolHistoryReplay.NONE)

            val messages = agent.convertEventsToMessages(history)

            messages.filterIsInstance<ToolResponseMessage>().single().responses.single().responseData() shouldBe "RESULT-1"
        }

        "should stop collecting chunks and still emit AgentFinishedEvent when shouldContinue flips to false mid-stream" {
            // Verifies the mid-stream guard: takeWhile { shouldContinue() } cancels the
            // upstream reactive stream as soon as the flag flips. Chunks produced after
            // the flip must not appear in the event list.
            val namespaceId = UUID.randomUUID()
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
            every { mockStreamSpec.chatResponse() } returns chatResponseFlux("A ", "B ", "C ", "D ", "E")

            val agent = makeAgent(agentId, mockChatClient)

            val events =
                agent
                    .run(
                        listOf(userMessage(namespaceId, caseId, "hello")),
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
