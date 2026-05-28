package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

class AgentIntentionGeneratorSpec :
    StringSpec({
        timeout = 5000

        fun makeGenerator() = AgentIntentionGenerator()

        fun makeContext(chatClient: ChatClient) =
            AgentAdvancedContext(
                chatClient = chatClient,
                tools = emptyList(),
                instructions = null,
                agentId = UUID.randomUUID(),
                confirmationManager = mockk(relaxed = true),
            )

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

        val validTools = listOf("FILES__ReadFile", "JIRA__GetIssue", "Answer")

        // -------------------------------------------------------------------------
        // parseIntentionAndTool unit tests
        // -------------------------------------------------------------------------

        "parseIntentionAndTool — nominal XML format" {
            val generator = makeGenerator()
            val response =
                """
                <intention>I need to read the file to answer the question.</intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent()

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldBe "I need to read the file to answer the question."
            toolName shouldBe "FILES__ReadFile"
        }

        "parseIntentionAndTool — tags on a single line" {
            val generator = makeGenerator()
            val response = "<intention>Done.</intention><toolName>Answer</toolName>"

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldBe "Done."
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — extra text outside tags is ignored" {
            val generator = makeGenerator()
            val response =
                """
                Here is my response:
                <intention>Fetching the Jira issue for context.</intention>
                <toolName>JIRA__GetIssue</toolName>
                Let me know if you need more.
                """.trimIndent()

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldBe "Fetching the Jira issue for context."
            toolName shouldBe "JIRA__GetIssue"
        }

        "parseIntentionAndTool — multi-line intention content" {
            val generator = makeGenerator()
            val response =
                """
                <intention>
                Step 1: check state.
                Step 2: call the tool.
                </intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent()

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldContain "Step 1"
            intention shouldContain "Step 2"
            toolName shouldBe "FILES__ReadFile"
        }

        "parseIntentionAndTool — unknown tool name falls back to Answer" {
            val generator = makeGenerator()
            val response =
                """
                <intention>Trying a non-existent tool.</intention>
                <toolName>UNKNOWN__Tool</toolName>
                """.trimIndent()

            val (_, toolName) = generator.parseIntentionAndTool(response, validTools)

            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — missing toolName tag throws AgentIntentionGenerationException" {
            val generator = makeGenerator()
            val response = "<intention>No tool tag present.</intention>"

            shouldThrow<AgentIntentionGenerationException> {
                generator.parseIntentionAndTool(response, validTools)
            }
        }

        "parseIntentionAndTool — missing intention tag uses full response as intention" {
            val generator = makeGenerator()
            val response = "<toolName>Answer</toolName>"

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldBe response.trim()
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — completely empty response throws AgentIntentionGenerationException" {
            val generator = makeGenerator()

            shouldThrow<AgentIntentionGenerationException> {
                generator.parseIntentionAndTool("", validTools)
            }
        }

        "parseIntentionAndTool — tool name matching is case-insensitive" {
            val generator = makeGenerator()
            val response =
                """
                <intention>Reading the file.</intention>
                <toolName>files__readfile</toolName>
                """.trimIndent()

            val (_, toolName) = generator.parseIntentionAndTool(response, validTools)

            toolName shouldBe "FILES__ReadFile"
        }

        // -------------------------------------------------------------------------
        // generate retry and fallback tests
        // -------------------------------------------------------------------------

        "generate retries on malformed LLM response and succeeds on second attempt" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany
                listOf(
                    "This is a malformed response with no XML tags at all",
                    "<intention>All good on retry.</intention><toolName>Answer</toolName>",
                )

            val context = makeContext(mockChatClient)
            val generator = makeGenerator()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = generator.generate(context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldContain "All good on retry"
        }

        "generate retries on LLM service failure and succeeds on second attempt" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            var callCount = 0
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } answers {
                callCount++
                if (callCount == 1) throw RuntimeException("Service unavailable")
                "<intention>Recovered after failure.</intention><toolName>Answer</toolName>"
            }

            val context = makeContext(mockChatClient)
            val generator = makeGenerator()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = generator.generate(context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldContain "Recovered after failure"
        }

        "generate falls back to Answer after all retry attempts exhausted" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "This is always malformed with no XML tags"

            val context = makeContext(mockChatClient)
            val generator = makeGenerator()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = generator.generate(context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldBe "This is always malformed with no XML tags"
        }

        "generate falls back to Answer after repeated LLM failures" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } throws RuntimeException("Persistent service failure")

            val context = makeContext(mockChatClient)
            val generator = makeGenerator()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = generator.generate(context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldBe "Unable to generate intention"
        }
    })
