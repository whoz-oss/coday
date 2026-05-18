package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.redirect.RedirectTool
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.toList
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.*

class AgentAdvancedSpec :
    StringSpec({
        timeout = 5000

        // -------------------------------------------------------------------------
        // Parsing unit tests — no ChatClient involved
        // -------------------------------------------------------------------------

        fun makeParserAgent(): AgentAdvanced {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            return AgentAdvanced(
                name = "ParserAgent",
                chatClient = mockChatClient,
                tools = emptyList(),
            )
        }

        val validTools = listOf("FILES__ReadFile", "JIRA__GetIssue", "Answer")

        "parseIntentionAndTool — nominal XML format" {
            val agent = makeParserAgent()
            val response =
                """
                <intention>I need to read the file to answer the question.</intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent()

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldBe "I need to read the file to answer the question."
            toolName shouldBe "FILES__ReadFile"
        }

        "parseIntentionAndTool — tags on a single line" {
            val agent = makeParserAgent()
            val response = "<intention>Done.</intention><toolName>Answer</toolName>"

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldBe "Done."
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — extra text outside tags is ignored" {
            val agent = makeParserAgent()
            val response =
                """
                Here is my response:
                <intention>Fetching the Jira issue for context.</intention>
                <toolName>JIRA__GetIssue</toolName>
                Let me know if you need more.
                """.trimIndent()

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldBe "Fetching the Jira issue for context."
            toolName shouldBe "JIRA__GetIssue"
        }

        "parseIntentionAndTool — multi-line intention content" {
            val agent = makeParserAgent()
            val response =
                """
                <intention>
                Step 1: check state.
                Step 2: call the tool.
                </intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent()

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldContain "Step 1"
            intention shouldContain "Step 2"
            toolName shouldBe "FILES__ReadFile"
        }

        "parseIntentionAndTool — unknown tool name falls back to Answer" {
            val agent = makeParserAgent()
            val response =
                """
                <intention>Trying a non-existent tool.</intention>
                <toolName>UNKNOWN__Tool</toolName>
                """.trimIndent()

            val (_, toolName) = agent.parseIntentionAndTool(response, validTools)

            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — missing toolName tag throws IntentionParsingException" {
            val agent = makeParserAgent()
            val response = "<intention>No tool tag present.</intention>"

            shouldThrow<AgentAdvanced.IntentionParsingException> {
                agent.parseIntentionAndTool(response, validTools)
            }
        }

        "parseIntentionAndTool — missing intention tag uses full response as intention" {
            val agent = makeParserAgent()
            val response = "<toolName>Answer</toolName>"

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldBe response.trim()
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — completely empty response throws IntentionParsingException" {
            val agent = makeParserAgent()

            shouldThrow<AgentAdvanced.IntentionParsingException> {
                agent.parseIntentionAndTool("", validTools)
            }
        }

        "parseIntentionAndTool — tool name matching is case-insensitive" {
            val agent = makeParserAgent()
            val response =
                """
                <intention>Reading the file.</intention>
                <toolName>files__readfile</toolName>
                """.trimIndent()

            val (_, toolName) = agent.parseIntentionAndTool(response, validTools)

            toolName shouldBe "FILES__ReadFile"
        }

        // -------------------------------------------------------------------------
        // Redirect contract
        // -------------------------------------------------------------------------

        "on redirect, AgentFinishedEvent is emitted before AgentSelectedEvent" {
            // AgentAdvanced.executeTool() must re-throw AgentInterrupt so run()'s catch block
            // handles it. The catch block must emit AgentFinishedEvent then AgentSelectedEvent
            // in that order — CaseRuntime.processNextStep scans newest-first, so
            // AgentSelectedEvent must appear after AgentFinishedEvent.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val redirectTool = RedirectTool(
                configName = "REDIRECT",
                eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
            )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            // First call() → intention selects the redirect tool
            // Second call() → parameter generation returns valid JSON for the tool
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany listOf(
                "<intention>Redirect to TargetAgent.</intention><toolName>REDIRECT__redirect</toolName>",
                """{"agentName":"TargetAgent"}""",
            )
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val agent = AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                name = "TestAgent",
                chatClient = mockChatClient,
                tools = listOf(redirectTool),
                maxIterations = 5,
            )

            val events = agent.run(
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User One", ActorRole.USER),
                        content = listOf(MessageContent.Text("do the thing")),
                    ),
                ),
            ).toList()

            val finishedIndex = events.indexOfFirst { it is AgentFinishedEvent }
            val selectedIndex = events.indexOfFirst { it is AgentSelectedEvent }

            (finishedIndex >= 0) shouldBe true
            (selectedIndex >= 0) shouldBe true
            // AgentFinishedEvent must come before AgentSelectedEvent
            (finishedIndex < selectedIndex) shouldBe true
            (events[selectedIndex] as AgentSelectedEvent).agentName shouldBe "TargetAgent"
        }

        "on redirect, no WarnEvent is emitted" {
            // AgentInterrupt is not an error — the catch block must not emit WarnEvent.
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val redirectTool = RedirectTool(
                configName = "REDIRECT",
                eligibleAgents = listOf(RedirectTool.EligibleAgent("TargetAgent", "does stuff")),
            )

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany listOf(
                "<intention>Redirect to TargetAgent.</intention><toolName>REDIRECT__redirect</toolName>",
                """{"agentName":"TargetAgent"}""",
            )
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.empty()

            val agent = AgentAdvanced(
                metadata = EntityMetadata(id = agentId),
                name = "TestAgent",
                chatClient = mockChatClient,
                tools = listOf(redirectTool),
                maxIterations = 5,
            )

            val events = agent.run(
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User One", ActorRole.USER),
                        content = listOf(MessageContent.Text("do the thing")),
                    ),
                ),
            ).toList()

            events.filterIsInstance<WarnEvent>() shouldHaveSize 0
        }

        // -------------------------------------------------------------------------
        // Orchestration integration tests
        // -------------------------------------------------------------------------

        fun makeInitialEvents(
            namespaceId: UUID,
            caseId: UUID,
        ) = listOf(
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = Actor("user1", "User One", ActorRole.USER),
                content = listOf(MessageContent.Text("Hello, can you help me?")),
            ),
        )

        "should complete full orchestration loop when LLM returns Answer tool in XML" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

            // call() is used for intention generation; stream() is used for the final answer
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "<intention>No further tool calls needed.</intention><toolName>Answer</toolName>"
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Here is ", "my answer.")

            val agent =
                AgentAdvanced(
                    metadata = EntityMetadata(id = agentId),
                    name = "TestAgent",
                    chatClient = mockChatClient,
                    tools = emptyList(),
                    maxIterations = 5,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            // AgentRunningEvent + ThinkingEvent + IntentionGeneratedEvent
            // + TextChunkEvent(x2) + MessageEvent + AgentFinishedEvent
            events shouldHaveSize 7

            val runningEvent = events[0] as? AgentRunningEvent
            runningEvent shouldNotBe null
            runningEvent!!.agentId shouldBe agentId

            val thinkingEvent = events[1] as? ThinkingEvent
            thinkingEvent shouldNotBe null

            val intentionEvent = events[2] as? IntentionGeneratedEvent
            intentionEvent shouldNotBe null
            intentionEvent!!.agentId shouldBe agentId
            intentionEvent.intention shouldContain "No further tool calls needed"
            intentionEvent.toolName shouldBe "Answer"

            val chunks = events.filterIsInstance<TextChunkEvent>()
            chunks shouldHaveSize 2
            chunks[0].chunk shouldBe "Here is "
            chunks[1].chunk shouldBe "my answer."

            val messageEvent = events.filterIsInstance<MessageEvent>().firstOrNull()
            messageEvent shouldNotBe null
            messageEvent!!.actor.id shouldBe agentId.toString()
            messageEvent.actor.role shouldBe ActorRole.AGENT
            (messageEvent.content.first() as MessageContent.Text).content shouldBe "Here is my answer."

            val finishedEvent = events.last() as? AgentFinishedEvent
            finishedEvent shouldNotBe null
            finishedEvent!!.agentId shouldBe agentId
            finishedEvent.agentName shouldBe "TestAgent"
        }

        "resolveIntentionAndTool retries on malformed LLM response and succeeds on second attempt" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany
                listOf(
                    "This is a malformed response with no XML tags at all",
                    "<intention>All good on retry.</intention><toolName>Answer</toolName>",
                )
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Done.")

            val agent =
                AgentAdvanced(
                    name = "RetryAgent",
                    chatClient = mockChatClient,
                    tools = emptyList(),
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val intentionEvent = events.filterIsInstance<IntentionGeneratedEvent>().first()
            intentionEvent.toolName shouldBe "Answer"
            intentionEvent.intention shouldContain "All good on retry"
        }

        "resolveIntentionAndTool retries on LLM service failure and succeeds on second attempt" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

            var callCount = 0
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } answers {
                callCount++
                if (callCount == 1) throw RuntimeException("Service unavailable")
                "<intention>Recovered after failure.</intention><toolName>Answer</toolName>"
            }
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Done.")

            val agent =
                AgentAdvanced(
                    name = "RetryAgent",
                    chatClient = mockChatClient,
                    tools = emptyList(),
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val intentionEvent = events.filterIsInstance<IntentionGeneratedEvent>().first()
            intentionEvent.toolName shouldBe "Answer"
            intentionEvent.intention shouldContain "Recovered after failure"
        }

        "resolveIntentionAndTool falls back to Answer after all retry attempts exhausted" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "This is always malformed with no XML tags"
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Fallback answer.")

            val agent =
                AgentAdvanced(
                    name = "FallbackAgent",
                    chatClient = mockChatClient,
                    tools = emptyList(),
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val intentionEvent = events.filterIsInstance<IntentionGeneratedEvent>().first()
            intentionEvent.toolName shouldBe "Answer"
            // intention should be the raw last response (trimmed)
            intentionEvent.intention shouldBe "This is always malformed with no XML tags"

            // Agent should still finish normally
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // detectRepetitionLoop unit tests
        // -------------------------------------------------------------------------

        "detectRepetitionLoop returns null when fewer than WINDOW tool responses" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-1",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-2",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                )

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "detectRepetitionLoop returns tool name when WINDOW consecutive same-tool responses" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                (1..AgentAdvanced.REPETITION_DETECTION_WINDOW).map { i ->
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-$i",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe "FILES__ReadFile"
        }

        "detectRepetitionLoop returns null when WINDOW consecutive responses are all failures" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                (1..AgentAdvanced.REPETITION_DETECTION_WINDOW).map { i ->
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-$i",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("Error: file not found"),
                        success = false,
                    )
                }

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "detectRepetitionLoop returns null when tools are mixed in the window" {
            val agent = makeParserAgent()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val events =
                listOf(
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-1",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-2",
                        toolName = "JIRA__GetIssue",
                        output = MessageContent.Text("content"),
                    ),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "req-3",
                        toolName = "FILES__ReadFile",
                        output = MessageContent.Text("content"),
                    ),
                )

            agent.detectRepetitionLoop(events) shouldBe null
        }

        "emits WarnEvent when repetition loop is detected and agent eventually selects Answer" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

            // Each tool iteration consumes 2 call().content() invocations:
            //   1st: resolveIntentionAndTool → returns toolName
            //   2nd: generateParameters → returns JSON args (any string works)
            // After 3 tool iterations (6 calls), the 7th call is the 4th intention → Answer.
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany
                listOf(
                    "<intention>Reading the file.</intention><toolName>FILES__ReadFile</toolName>", // iter 1 intention
                    "{}", // iter 1 params
                    "<intention>Reading the file again.</intention><toolName>FILES__ReadFile</toolName>", // iter 2 intention
                    "{}", // iter 2 params
                    "<intention>Reading the file yet again.</intention><toolName>FILES__ReadFile</toolName>", // iter 3 intention
                    "{}", // iter 3 params
                    "<intention>Stopping due to loop.</intention><toolName>Answer</toolName>", // iter 4 intention (after WarnEvent)
                )
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Loop stopped.")

            val mockTool = mockk<io.whozoss.agentos.sdk.tool.StandardTool<String>>(relaxed = true)
            every { mockTool.name } returns "FILES__ReadFile"
            every { mockTool.description } returns "Read a file"
            every { mockTool.inputSchema } returns "{}"
            every { mockTool.paramType } returns String::class.java
            every { mockTool.executeWithJson(any(), any()) } returns "file content"

            val agent =
                AgentAdvanced(
                    name = "LoopAgent",
                    chatClient = mockChatClient,
                    tools = listOf(mockTool),
                    maxIterations = 10,
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val warnEvents = events.filterIsInstance<WarnEvent>()
            warnEvents shouldHaveSize 1
            warnEvents.first().message shouldContain "FILES__ReadFile"
            warnEvents.first().message shouldContain "consecutively"

            val intentionEvents = events.filterIsInstance<IntentionGeneratedEvent>()
            intentionEvents.last().toolName shouldBe "Answer"

            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }

        "resolveIntentionAndTool falls back to Answer after repeated LLM failures" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val mockStreamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } throws RuntimeException("Persistent service failure")
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Fallback answer.")

            val agent =
                AgentAdvanced(
                    name = "FallbackAgent",
                    chatClient = mockChatClient,
                    tools = emptyList(),
                )

            val events = agent.run(makeInitialEvents(namespaceId, caseId)).toList()

            val intentionEvent = events.filterIsInstance<IntentionGeneratedEvent>().first()
            intentionEvent.toolName shouldBe "Answer"
            // No lastResponse captured since all calls threw — falls back to default message
            intentionEvent.intention shouldBe "Unable to generate intention"

            // Agent should still finish normally
            events.filterIsInstance<AgentFinishedEvent>() shouldHaveSize 1
        }
    })
