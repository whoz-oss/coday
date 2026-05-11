package io.whozoss.agentos.agent

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

        "parseIntentionAndTool — missing toolName tag falls back to Answer" {
            val agent = makeParserAgent()
            val response = "<intention>No tool tag present.</intention>"

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldBe "No tool tag present."
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — missing intention tag uses full response as intention" {
            val agent = makeParserAgent()
            val response = "<toolName>Answer</toolName>"

            val (intention, toolName) = agent.parseIntentionAndTool(response, validTools)

            intention shouldBe response.trim()
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — completely empty response falls back gracefully" {
            val agent = makeParserAgent()

            val (_, toolName) = agent.parseIntentionAndTool("", validTools)

            toolName shouldBe "Answer"
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
        // Orchestration integration test
        // -------------------------------------------------------------------------

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

            val initialEvents =
                listOf(
                    MessageEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        actor = Actor("user1", "User One", ActorRole.USER),
                        content = listOf(MessageContent.Text("Hello, can you help me?")),
                    ),
                )

            val events = agent.run(initialEvents).toList()

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
    })
