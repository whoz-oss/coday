package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.*

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

        // Recovery selects the last complete adjacent pair, not independent tags.
        val recoveryCases = listOf(
            Triple(
                "nested intention tags are preserved inside the outer decision",
                """
                <intention>
                Some reasoning containing <intention>an example</intention>.
                </intention>
                <toolName>Answer</toolName>
                """.trimIndent(),
                "Some reasoning containing <intention>an example</intention>." to "Answer",
            ),
            Triple(
                "multiple nesting levels and sibling examples are preserved",
                "<intention>Use <intention>outer <intention>inner</intention></intention> and <intention>another</intention>.</intention><toolName>Answer</toolName>",
                "Use <intention>outer <intention>inner</intention></intention> and <intention>another</intention>." to "Answer",
            ),
            Triple(
                "a completed outer decision takes precedence over its embedded pair",
                "<intention>Example: <intention>Read.</intention><toolName>FILES__ReadFile</toolName> Instead, answer.</intention><toolName>Answer</toolName>",
                "Example: <intention>Read.</intention><toolName>FILES__ReadFile</toolName> Instead, answer." to "Answer",
            ),
            Triple(
                "unmatched closing tags do not prevent recovery",
                "</intention><toolName>FILES__ReadFile</toolName><intention>Done.</intention><toolName>Answer</toolName>",
                "Done." to "Answer",
            ),
            Triple(
                "an extra toolName does not replace the paired tool",
                """
                <intention>Read the file.</intention>
                <toolName>Answer</toolName>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent(),
                "Read the file." to "Answer",
            ),
            Triple(
                "an unpaired intention before the decision is ignored",
                """
                <intention>First intention.</intention>
                <intention>Second intention.</intention>
                <toolName>Answer</toolName>
                """.trimIndent(),
                "Second intention." to "Answer",
            ),
            Triple(
                "the last of several complete pairs is selected",
                """
                <intention>First intention.</intention>
                <toolName>Answer</toolName>
                <intention>Second intention.</intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent(),
                "Second intention." to "FILES__ReadFile",
            ),
            Triple(
                "trailing Done is tolerated",
                """
                <intention>Finished.</intention>
                <toolName>Answer</toolName>
                Done
                """.trimIndent(),
                "Finished." to "Answer",
            ),
            Triple(
                "an unfinished outer intention is skipped",
                """
                <intention>Long unfinished reasoning.
                <intention>The final decision.</intention>
                <toolName>Answer</toolName>
                """.trimIndent(),
                "The final decision." to "Answer",
            ),
            Triple(
                "a quoted format example before the decision is skipped",
                """
                Wait, the output format is:
                <intention>...</intention>
                <toolName>Answer</toolName>
                More reasoning.
                <intention>Read the file first.</intention>
                <toolName>FILES__ReadFile</toolName>
                """.trimIndent(),
                "Read the file first." to "FILES__ReadFile",
            ),
            Triple(
                "an unfinished wrapper with an example and trailing text is recovered",
                """
                <intention>Long reasoning referencing <ProfileCaretaker_tools>.
                Wait, the output format is:
                <intention>...</intention>
                <toolName>Answer</toolName>
                More reasoning about the missing tools.
                <intention>Ask the user for the profile content.</intention>
                <toolName>Answer</toolName>
                Done
                """.trimIndent(),
                "Ask the user for the profile content." to "Answer",
            ),
            Triple(
                "other XML-like tags in the intention are preserved",
                "<intention>Follow <instructions> and <ProfileCaretaker_tools>.</intention><toolName>Answer</toolName>",
                "Follow <instructions> and <ProfileCaretaker_tools>." to "Answer",
            ),
            Triple(
                "whitespace is trimmed and tool casing is canonicalized",
                "<intention>  Read the file.  </intention>\n\t<toolName>  files__readfile  </toolName>",
                "Read the file." to "FILES__ReadFile",
            ),
            Triple(
                "an incomplete trailing pair does not replace the last complete pair",
                """
                <intention>Complete decision.</intention>
                <toolName>Answer</toolName>
                <intention>Unfinished decision.</intention>
                <toolName>
                """.trimIndent(),
                "Complete decision." to "Answer",
            ),
        )

        recoveryCases.forEach { (description, response, expected) ->
            "parseIntentionAndTool — $description" {
                makeGenerator().parseIntentionAndTool(response, validTools) shouldBe expected
            }
        }

        val invalidPairCases = listOf(
            "separated tags are not combined into a pair" to
                "<intention>Decision.</intention>Unrelated text<toolName>Answer</toolName>",
            "reversed tags are not combined into a pair" to
                "<toolName>Answer</toolName><intention>Decision.</intention>",
            "empty intention is rejected" to
                "<intention> \n </intention><toolName>Answer</toolName>",
            "a blank response is rejected" to " \n\t ",
        )

        invalidPairCases.forEach { (description, response) ->
            "parseIntentionAndTool — $description" {
                val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                    makeGenerator().parseIntentionAndTool(response, validTools)
                }
                ex.response shouldBe response
            }
        }

        "parseIntentionAndTool — an unknown tool in the last pair does not fall back to an earlier pair" {
            val response =
                """
                <intention>Earlier example.</intention>
                <toolName>Answer</toolName>
                <intention>Actual decision.</intention>
                <toolName>UNKNOWN__Tool</toolName>
                Done
                """.trimIndent()

            val ex = shouldThrow<AgentIntentionGenerationException.UnknownTool> {
                makeGenerator().parseIntentionAndTool(response, validTools)
            }
            ex.toolName shouldBe "UNKNOWN__Tool"
            ex.response shouldBe response
        }

        "parseIntentionAndTool — a trailing format example is indistinguishable from a decision" {
            // Document the recovery heuristic's limitation: position, not meaning,
            // determines which complete pair is selected.
            val response =
                """
                <intention>Read the file.</intention>
                <toolName>FILES__ReadFile</toolName>
                The output format is:
                <intention>...</intention>
                <toolName>Answer</toolName>
                """.trimIndent()

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("..." to "Answer")
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
                "agent",
                makeContext(mockChatClient),
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
                "agent",
                makeContext(mockChatClient),
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
                "agent",
                makeContext(mockChatClient),
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

            val result = generator.generate(
                "agent",
                context,
                makeInitialEvents(namespaceId, caseId),
                namespaceId,
                caseId
            )

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

            val result = generator.generate("agent", context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

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

            val result = generator.generate("agent", context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            result.toolName shouldBe "Answer"
            result.intention shouldContain "Failed to plan next step after"
            result.intention shouldContain "Expected <intention>...</intention> followed by <toolName>...</toolName>"
            result.isFailedIntention shouldBe true
        }

        // -------------------------------------------------------------------------
        // redirect guideline — prompt rendering
        // -------------------------------------------------------------------------

        "generate — redirectGuideline present: prompt contains the guideline block" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>Redirect after task.</intention><toolName>Answer</toolName>"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val context = AgentAdvancedContext(
                chatClient = mockChatClient,
                tools = emptyList(),
                instructions = null,
                agentId = UUID.randomUUID(),
                confirmationManager = mockk(relaxed = true),
                redirectGuideline = "When done, redirect to TRSharing.",
            )

            makeGenerator().generate("agent", context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            // The guideline block (header + content) is injected only when redirectGuideline is non-blank.
            promptSlot.captured.contents shouldContain "### Redirect Guideline"
            promptSlot.captured.contents shouldContain "<redirect_guideline>"
            promptSlot.captured.contents shouldContain "When done, redirect to TRSharing."
        }

        "generate — redirectGuideline null: prompt does not contain the guideline block" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>No redirect needed.</intention><toolName>Answer</toolName>"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            makeGenerator().generate("agent", makeContext(mockChatClient), makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            // Reinforced: the reference to <redirect_guideline> in the Agent Handoff reasoning step
            // is now conditional too, so absolutely no occurrence of "redirect_guideline" (tag or
            // title) should leak into the prompt when no guideline is configured.
            val contents = promptSlot.captured.contents
            contents shouldNotContain "### Redirect Guideline"
            contents shouldNotContain "redirect_guideline"
        }

        "generate — redirectGuideline blank: prompt does not contain the guideline block" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns "<intention>No redirect needed.</intention><toolName>Answer</toolName>"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val context = AgentAdvancedContext(
                chatClient = mockChatClient,
                tools = emptyList(),
                instructions = null,
                agentId = UUID.randomUUID(),
                confirmationManager = mockk(relaxed = true),
                redirectGuideline = "   ",
            )

            makeGenerator().generate("agent", context, makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            val contents = promptSlot.captured.contents
            contents shouldNotContain "### Redirect Guideline"
            contents shouldNotContain "redirect_guideline"
        }
    })
