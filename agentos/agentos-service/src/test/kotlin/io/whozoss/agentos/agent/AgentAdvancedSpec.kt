package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
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
            } returnsMany listOf(
                "This is a malformed response with no XML tags at all",
                "<intention>All good on retry.</intention><toolName>Answer</toolName>",
            )
            every { mockChatClient.prompt(any<Prompt>()).stream() } returns mockStreamSpec
            every { mockStreamSpec.content() } returns Flux.just("Done.")

            val agent = AgentAdvanced(
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

            val agent = AgentAdvanced(
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

            val agent = AgentAdvanced(
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

            val agent = AgentAdvanced(
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
