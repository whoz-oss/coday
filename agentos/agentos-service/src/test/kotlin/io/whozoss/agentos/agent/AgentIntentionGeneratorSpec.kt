package io.whozoss.agentos.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.agent.AgentIntentionGenerator.Companion.INTENTION_TAG_NAME
import io.whozoss.agentos.agent.AgentIntentionGenerator.Companion.TOOL_TAG_NAME
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
            // Keep this contract test literal: it independently verifies the public XML format.
            INTENTION_TAG_NAME shouldBe "intention"
            TOOL_TAG_NAME shouldBe "tool"
            val response =
                """
                <intention>I need to read the file to answer the question.</intention>
                <tool>FILES__ReadFile</tool>
                """.trimIndent()

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldBe "I need to read the file to answer the question."
            toolName shouldBe "FILES__ReadFile"
        }

        "parseIntentionAndTool — tags on a single line" {
            val generator = makeGenerator()
            val response = "<$INTENTION_TAG_NAME>Done.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

            val (intention, toolName) = generator.parseIntentionAndTool(response, validTools)

            intention shouldBe "Done."
            toolName shouldBe "Answer"
        }

        "parseIntentionAndTool — extra text outside tags is ignored" {
            val generator = makeGenerator()
            val response =
                """
                Here is my response:
                <$INTENTION_TAG_NAME>Fetching the Jira issue for context.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>JIRA__GetIssue</$TOOL_TAG_NAME>
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
                <$INTENTION_TAG_NAME>
                Step 1: check state.
                Step 2: call the tool.
                </$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME>
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
                <$INTENTION_TAG_NAME>Trying a non-existent tool.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>UNKNOWN__Tool</$TOOL_TAG_NAME>
                """.trimIndent()

            val ex = shouldThrow<AgentIntentionGenerationException.UnknownTool> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.toolName shouldBe "UNKNOWN__Tool"
            ex.response shouldBe response
        }

        "parseIntentionAndTool — missing tool tag throws InvalidFormat with response" {
            val generator = makeGenerator()
            val response = "<$INTENTION_TAG_NAME>No tool tag present.</$INTENTION_TAG_NAME>"

            val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                generator.parseIntentionAndTool(response, validTools)
            }
            ex.response shouldBe response
        }

        "parseIntentionAndTool — missing intention tag throws InvalidFormat with response" {
            val generator = makeGenerator()
            val response = "<$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
                <$INTENTION_TAG_NAME>
                Some reasoning containing <$INTENTION_TAG_NAME>an example</$INTENTION_TAG_NAME>.
                </$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                """.trimIndent(),
                "Some reasoning containing <$INTENTION_TAG_NAME>an example</$INTENTION_TAG_NAME>." to "Answer",
            ),
            Triple(
                "multiple nesting levels and sibling examples are preserved",
                "<$INTENTION_TAG_NAME>Use <$INTENTION_TAG_NAME>outer <$INTENTION_TAG_NAME>inner</$INTENTION_TAG_NAME></$INTENTION_TAG_NAME> and <$INTENTION_TAG_NAME>another</$INTENTION_TAG_NAME>.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
                "Use <$INTENTION_TAG_NAME>outer <$INTENTION_TAG_NAME>inner</$INTENTION_TAG_NAME></$INTENTION_TAG_NAME> and <$INTENTION_TAG_NAME>another</$INTENTION_TAG_NAME>." to "Answer",
            ),
            Triple(
                "a completed outer decision takes precedence over its embedded pair",
                "<$INTENTION_TAG_NAME>Example: <$INTENTION_TAG_NAME>Read.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME> Instead, answer.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
                "Example: <$INTENTION_TAG_NAME>Read.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME> Instead, answer." to "Answer",
            ),
            Triple(
                "unmatched closing tags do not prevent recovery",
                "</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME><$INTENTION_TAG_NAME>Done.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
                "Done." to "Answer",
            ),
            Triple(
                "an extra tool tag does not replace the paired tool",
                """
                <$INTENTION_TAG_NAME>Read the file.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                <$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME>
                """.trimIndent(),
                "Read the file." to "Answer",
            ),
            Triple(
                "an unpaired intention before the decision is ignored",
                """
                <$INTENTION_TAG_NAME>First intention.</$INTENTION_TAG_NAME>
                <$INTENTION_TAG_NAME>Second intention.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                """.trimIndent(),
                "Second intention." to "Answer",
            ),
            Triple(
                "the last of several complete pairs is selected",
                """
                <$INTENTION_TAG_NAME>First intention.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                <$INTENTION_TAG_NAME>Second intention.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME>
                """.trimIndent(),
                "Second intention." to "FILES__ReadFile",
            ),
            Triple(
                "trailing Done is tolerated",
                """
                <$INTENTION_TAG_NAME>Finished.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                Done
                """.trimIndent(),
                "Finished." to "Answer",
            ),
            Triple(
                "an unfinished outer intention is skipped",
                """
                <$INTENTION_TAG_NAME>Long unfinished reasoning.
                <$INTENTION_TAG_NAME>The final decision.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                """.trimIndent(),
                "The final decision." to "Answer",
            ),
            Triple(
                "a quoted format example before the decision is skipped",
                """
                Wait, the output format is:
                <$INTENTION_TAG_NAME>...</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                More reasoning.
                <$INTENTION_TAG_NAME>Read the file first.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME>
                """.trimIndent(),
                "Read the file first." to "FILES__ReadFile",
            ),
            Triple(
                "an unfinished wrapper with an example and trailing text is recovered",
                """
                <$INTENTION_TAG_NAME>Long reasoning referencing <ProfileCaretaker_tools>.
                Wait, the output format is:
                <$INTENTION_TAG_NAME>...</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                More reasoning about the missing tools.
                <$INTENTION_TAG_NAME>Ask the user for the profile content.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                Done
                """.trimIndent(),
                "Ask the user for the profile content." to "Answer",
            ),
            Triple(
                "other XML-like tags in the intention are preserved",
                "<$INTENTION_TAG_NAME>Follow <instructions> and <ProfileCaretaker_tools>.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
                "Follow <instructions> and <ProfileCaretaker_tools>." to "Answer",
            ),
            Triple(
                "whitespace is trimmed and tool casing is canonicalized",
                "<$INTENTION_TAG_NAME>  Read the file.  </$INTENTION_TAG_NAME>\n\t<$TOOL_TAG_NAME>  files__readfile  </$TOOL_TAG_NAME>",
                "Read the file." to "FILES__ReadFile",
            ),
            Triple(
                "an incomplete trailing pair does not replace the last complete pair",
                """
                <$INTENTION_TAG_NAME>Complete decision.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                <$INTENTION_TAG_NAME>Unfinished decision.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>
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
                "<$INTENTION_TAG_NAME>Decision.</$INTENTION_TAG_NAME>Unrelated text<$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
            "reversed tags are not combined into a pair" to
                "<$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME><$INTENTION_TAG_NAME>Decision.</$INTENTION_TAG_NAME>",
            "empty intention is rejected" to
                "<$INTENTION_TAG_NAME> \n </$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
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
                <$INTENTION_TAG_NAME>Earlier example.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
                <$INTENTION_TAG_NAME>Actual decision.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>UNKNOWN__Tool</$TOOL_TAG_NAME>
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
                <$INTENTION_TAG_NAME>Read the file.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>FILES__ReadFile</$TOOL_TAG_NAME>
                The output format is:
                <$INTENTION_TAG_NAME>...</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>
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
                <$INTENTION_TAG_NAME>Reading the file.</$INTENTION_TAG_NAME>
                <$TOOL_TAG_NAME>files__readfile</$TOOL_TAG_NAME>
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
            } returns "<$INTENTION_TAG_NAME>Handling user follow-up.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
            } returns "<$INTENTION_TAG_NAME>Processing user answer.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
            } returns "<$INTENTION_TAG_NAME>Continuing after successful tool call.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
            val prompts = mutableListOf<Prompt>()
            every {
                mockChatClient.prompt(capture(prompts)).call().content()
            } returnsMany
                listOf(
                    "This is a malformed response with no XML tags at all",
                    "<$INTENTION_TAG_NAME>All good on retry.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
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
            prompts.size shouldBe 2
            prompts.forEach { prompt ->
                prompt.contents shouldContain "<$TOOL_TAG_NAME>[The exact name of the tool to be called]</$TOOL_TAG_NAME>"
                // The obsolete tag must not appear in the generated prompt.
                prompt.contents shouldNotContain "<toolName>"
                prompt.contents shouldNotContain "</toolName>"
            }
            prompts.last().contents shouldContain "Expected <$INTENTION_TAG_NAME>...</$INTENTION_TAG_NAME> followed by <$TOOL_TAG_NAME>...</$TOOL_TAG_NAME>"
        }

        "generate retries on malformed response and succeeds on second attempt with unknown tool" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returnsMany listOf(
                "<$INTENTION_TAG_NAME>Trying unknown tool.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>UNKNOWN__Tool</$TOOL_TAG_NAME>",
                "<$INTENTION_TAG_NAME>Recovered on retry.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>",
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
            result.intention shouldContain "Expected <$INTENTION_TAG_NAME>...</$INTENTION_TAG_NAME> followed by <$TOOL_TAG_NAME>...</$TOOL_TAG_NAME>"
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
            } returns "<$INTENTION_TAG_NAME>Redirect after task.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
            } returns "<$INTENTION_TAG_NAME>No redirect needed.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
            } returns "<$INTENTION_TAG_NAME>No redirect needed.</$INTENTION_TAG_NAME><$TOOL_TAG_NAME>Answer</$TOOL_TAG_NAME>"

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
