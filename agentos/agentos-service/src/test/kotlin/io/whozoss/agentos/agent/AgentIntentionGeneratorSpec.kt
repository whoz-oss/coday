package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import io.whozoss.agentos.chat.CompressingChatClient
import io.whozoss.agentos.util.IdCompressorService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.*

class AgentIntentionGeneratorSpec :
    StringSpec({
        timeout = 5000

        fun makeGenerator() = AgentIntentionGenerator()
        fun makeCompressingClient(chatClient: ChatClient) = CompressingChatClient(chatClient, IdCompressorService())

        fun makeContext(chatClient: ChatClient) =
            AgentAdvancedContext(
                chatClient = chatClient,
                tools = emptyList(),
                instructions = null,
                agentId = UUID.randomUUID(),
                confirmationManager = mockk(relaxed = true),
            )

        val userActor = Actor("user1", "User One", ActorRole.USER)

        fun makeInitialEvents(
            namespaceId: UUID,
            caseId: UUID,
        ) = listOf(
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = userActor,
                content = listOf(MessageContent.Text("Hello, can you help me?")),
            ),
        )

        // Tool call succeeded, then user sends a new message
        fun makeEventsWithUserMessageAfterToolCall(
            namespaceId: UUID,
            caseId: UUID,
        ): List<CaseEvent> {
            val toolRequestId = "req-1"
            return listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("Please help me.")),
                ),
                ToolRequestEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = "FILES__ReadFile",
                    args = "{}",
                ),
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = "FILES__ReadFile",
                    output = MessageContent.Text("file content"),
                    success = true,
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("Actually, do something else.")),
                ),
            )
        }

        // Tool call (question tool) issued, then user answers — no ToolResponseEvent
        fun makeEventsWithAnswerAfterToolCall(
            namespaceId: UUID,
            caseId: UUID,
        ): List<CaseEvent> {
            val agentId = UUID.randomUUID()
            val questionEvent = QuestionEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = "TestAgent",
                question = "Which file should I read?",
                options = null,
            )
            return listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("Please help me.")),
                ),
                ToolRequestEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "req-1",
                    toolName = "AskQuestion",
                    args = "{}",
                ),
                questionEvent,
                questionEvent.createAnswer(userActor, "readme.txt"),
            )
        }

        // User message appears before the last tool call — must NOT trigger the user-interaction branch
        fun makeEventsWithUserMessageBeforeToolCall(
            namespaceId: UUID,
            caseId: UUID,
        ): List<CaseEvent> {
            val toolRequestId = "req-1"
            return listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("Please help me.")),
                ),
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text("Actually ignore that.")),
                ),
                ToolRequestEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = "FILES__ReadFile",
                    args = "{}",
                ),
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = toolRequestId,
                    toolName = "FILES__ReadFile",
                    output = MessageContent.Text("file content"),
                    success = true,
                ),
            )
        }

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

        "parseIntentionAndTool — unknown tool name throws UnknownTool with tool name and response" {
            val generator = makeGenerator()
            val response =
                """
                <intention>Trying a non-existent tool.</intention>
                <toolName>UNKNOWN__Tool</toolName>
                """.trimIndent()

            val ex = shouldThrow<AgentIntentionGenerationException.UnknownTool> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.toolName shouldBe "UNKNOWN__Tool"
            ex.response shouldBe response
        }

        "parseIntentionAndTool — missing toolName tag throws InvalidFormat with response" {
            val generator = makeGenerator()
            val response = "<intention>No tool tag present.</intention>"

            val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.response shouldBe response
        }

        "parseIntentionAndTool — missing intention tag throws InvalidFormat with response" {
            val generator = makeGenerator()
            val response = "<toolName>Answer</toolName>"

            val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.response shouldBe response
        }

        "parseIntentionAndTool — two toolName tags throws InvalidFormat" {
            val generator = makeGenerator()
            val response =
                """
                <intention>I need to read the file to answer the question.</intention>
                <toolName>Answer</toolName>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent()

            val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.message shouldContain "Multiple <toolName> tags found"
            ex.response shouldBe response
        }

        "parseIntentionAndTool — two intention tags throws InvalidFormat" {
            val generator = makeGenerator()
            val response =
                """
                <intention>First intention.</intention>
                <intention>Second intention.</intention>
                <toolName>Answer</toolName>
                """.trimIndent()

            val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.message shouldContain "Multiple <intention> tags found"
            ex.response shouldBe response
        }

        "parseIntentionAndTool — two toolName and two intention tags throws InvalidFormat" {
            val generator = makeGenerator()
            val response =
                """
                <intention>First intention.</intention>
                <toolName>Answer</toolName>
                <intention>Second intention.</intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent()

            shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                generator.parseIntentionAndTool(response, validTools)
            }
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
        // executionState — user interaction branches
        // -------------------------------------------------------------------------

        "generate — executionState reflects user MessageEvent posted after last tool call" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>Handling user follow-up.</intention><toolName>Answer</toolName>"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            makeGenerator().generate(
                makeContext(mockChatClient),
                makeCompressingClient(mockChatClient),
                makeEventsWithUserMessageAfterToolCall(namespaceId, caseId),
                namespaceId,
                caseId,
            )

            promptSlot.captured.contents shouldContain "The user has just sent a new message"
        }

        "generate — executionState reflects AnswerEvent posted after last tool call" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>Processing user answer.</intention><toolName>Answer</toolName>"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            makeGenerator().generate(
                makeContext(mockChatClient),
                makeCompressingClient(mockChatClient),
                makeEventsWithAnswerAfterToolCall(namespaceId, caseId),
                namespaceId,
                caseId,
            )

            promptSlot.captured.contents shouldContain "The user has just answered a question"
        }

        "generate — user message before last tool call does not trigger user-interaction branch" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>Continuing after successful tool call.</intention><toolName>Answer</toolName>"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            makeGenerator().generate(
                makeContext(mockChatClient),
                makeCompressingClient(mockChatClient),
                makeEventsWithUserMessageBeforeToolCall(namespaceId, caseId),
                namespaceId,
                caseId,
            )

            promptSlot.captured.contents shouldContain "Last tool 'FILES__ReadFile' executed without technical issue"
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

            val result = generator.generate(context, makeCompressingClient(mockChatClient), makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldContain "All good on retry"
            result.isFailedIntention shouldBe false
        }

        "generate retries on malformed response and succeeds on second attempt with unknown tool" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany listOf(
                "<intention>Trying unknown tool.</intention><toolName>UNKNOWN__Tool</toolName>",
                "<intention>Recovered on retry.</intention><toolName>Answer</toolName>",
            )

            val context = makeContext(mockChatClient)
            val generator = makeGenerator()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = generator.generate(context, makeCompressingClient(mockChatClient), makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldContain "Recovered on retry"
            result.isFailedIntention shouldBe false
        }

        "generate falls back to Answer after all retry attempts exhausted with meaningful intention" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "This is always malformed with no XML tags"

            val context = makeContext(mockChatClient)
            val generator = makeGenerator()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = generator.generate(context, makeCompressingClient(mockChatClient), makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldContain "Failed to plan next step after"
            result.intention shouldContain "Missing <toolName> tag"
            result.isFailedIntention shouldBe true
        }

        // -------------------------------------------------------------------------
        // ID compression in intention generation
        // -------------------------------------------------------------------------

        "generate sends compressed IDs to the LLM and uncompresses the response before parsing" {
            // Strategy: two-pass approach using the SAME agentId and context so that the
            // byte offset of realUuid in the serialised messages is identical in both passes,
            // guaranteeing the same alias is assigned.
            //   Pass 1: capture the real prompt and extract the alias that replaced realUuid.
            //   Pass 2: re-run generate() with the LLM mocked to echo that alias back, and
            //           verify that decompression restores the original UUID.
            val realUuid = "550e8400-e29b-41d4-a716-446655440000"
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            // Fixed agentId shared across both passes so the system-prompt content
            // (and therefore byte offsets) is identical.
            val agentId = UUID.randomUUID()
            val sharedConfirmationManager = mockk<ConfirmationManager>(relaxed = true)

            val events = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text(realUuid)),
                ),
            )

            // --- Pass 1: capture the compressed prompt to learn the actual alias ---
            val captureSlot = slot<Prompt>()
            val captureClient = mockk<ChatClient>(relaxed = true)
            every {
                captureClient.prompt(capture(captureSlot)).call().content()
            } returns "<intention>Done.</intention><toolName>Answer</toolName>"

            AgentIntentionGenerator()
                .generate(
                    AgentAdvancedContext(
                        chatClient = captureClient,
                        tools = emptyList(),
                        instructions = null,
                        agentId = agentId,
                        confirmationManager = sharedConfirmationManager,
                    ),
                    CompressingChatClient(captureClient, IdCompressorService()),
                    events, namespaceId, caseId,
                )

            // Extract the alias: the UI-prefixed token that replaced the UUID in the prompt.
            val capturedText = captureSlot.captured.instructions.joinToString(" ") { it.text }
            capturedText.contains(realUuid) shouldBe false   // sanity: UUID must be gone
            val alias = Regex("UI[0-9a-z]+").find(capturedText)?.value
                ?: error("No compressed alias (UI...) found in captured prompt")

            // --- Pass 2: verify decompression restores the original UUID ---
            // Same agentId + same tools (empty) + same events → same byte offsets → same alias.
            val echoClient = mockk<ChatClient>(relaxed = true)
            every {
                echoClient.prompt(any<Prompt>()).call().content()
            } returns "<intention>Profile $alias updated.</intention><toolName>Answer</toolName>"

            val result = AgentIntentionGenerator()
                .generate(
                    AgentAdvancedContext(
                        chatClient = echoClient,
                        tools = emptyList(),
                        instructions = null,
                        agentId = agentId,          // same agentId — identical system prompt
                        confirmationManager = sharedConfirmationManager,
                    ),
                    CompressingChatClient(echoClient, IdCompressorService()),
                    events, namespaceId, caseId,
                )

            // The stored intention must contain the original UUID, not the alias.
            result.intention shouldContain realUuid
            result.intention.contains(alias) shouldBe false
            result.toolName shouldBe "Answer"
        }

        "generate sends compressed IDs in the prompt — LLM never sees raw UUIDs" {
            // Verify the prompt actually sent to the LLM contains the alias, not the UUID.
            val realUuid = "550e8400-e29b-41d4-a716-446655440000"
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val agentId = UUID.randomUUID()

            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>Done.</intention><toolName>Answer</toolName>"

            val context = AgentAdvancedContext(
                chatClient = mockChatClient,
                tools = emptyList(),
                instructions = null,
                agentId = agentId,
                confirmationManager = mockk(relaxed = true),
            )
            val events = listOf(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = userActor,
                    content = listOf(MessageContent.Text(realUuid)),
                ),
            )

            // Inject the compressor — without it, no compression happens and the UUID would appear raw.
            AgentIntentionGenerator()
                .generate(context, CompressingChatClient(mockChatClient, IdCompressorService()), events, namespaceId, caseId)

            // The raw UUID must NOT appear in any message sent to the LLM.
            val promptText = promptSlot.captured.instructions.joinToString(" ") { it.text }
            promptText.contains(realUuid) shouldBe false
            // A compressed alias (UI prefix) must be present instead.
            promptText.contains("UI") shouldBe true
        }
    })
