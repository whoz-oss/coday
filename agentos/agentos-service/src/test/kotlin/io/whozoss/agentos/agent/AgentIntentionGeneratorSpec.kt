package io.whozoss.agentos.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.agent.AgentIntentionGenerator.Companion.EXPECTED_FORMAT_MESSAGE
import io.whozoss.agentos.agent.AgentIntentionGenerator.Companion.INTENTION_FIELD
import io.whozoss.agentos.agent.AgentIntentionGenerator.Companion.TOOL_FIELD
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.*
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.*

class AgentIntentionGeneratorSpec :
    StringSpec({
        timeout = 5000

        val mapper = jacksonObjectMapper()

        /** Builds a well-formed decision, escaping the content exactly as a model should. */
        fun decision(
            intention: String,
            tool: String,
        ): String = mapper.writeValueAsString(linkedMapOf(INTENTION_FIELD to intention, TOOL_FIELD to tool))

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

        "parseIntentionAndTool — nominal JSON format" {
            // Keep this contract test literal: it independently verifies the public JSON format.
            INTENTION_FIELD shouldBe "intention"
            TOOL_FIELD shouldBe "tool"
            val response = """{"intention": "I need to read the file to answer the question.", "tool": "FILES__ReadFile"}"""

            val (intention, toolName) = makeGenerator().parseIntentionAndTool(response, validTools)

            intention shouldBe "I need to read the file to answer the question."
            toolName shouldBe "FILES__ReadFile"
        }

        "parseIntentionAndTool — pretty-printed multi-line object" {
            val response =
                """
                {
                  "intention": "Fetching the Jira issue for context.",
                  "tool": "JIRA__GetIssue"
                }
                """.trimIndent()

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe
                ("Fetching the Jira issue for context." to "JIRA__GetIssue")
        }

        "parseIntentionAndTool — field order does not matter" {
            val response = """{"tool": "Answer", "intention": "Tool first."}"""

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Tool first." to "Answer")
        }

        "parseIntentionAndTool — escaped quotes, braces and line breaks in the intention are decoded" {
            val text = "Use {braces}, \"quotes\" and <instructions>.\nSecond line."

            makeGenerator().parseIntentionAndTool(decision(text, "Answer"), validTools) shouldBe (text to "Answer")
        }

        "parseIntentionAndTool — a markdown code fence around the object is tolerated" {
            val response = "```json\n${decision("Fenced decision.", "Answer")}\n```"

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Fenced decision." to "Answer")
        }

        "parseIntentionAndTool — prose before and after the object is tolerated" {
            val response = "Here is my response:\n${decision("Prose around.", "Answer")}\nLet me know if you need more."

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Prose around." to "Answer")
        }

        "parseIntentionAndTool — surrounding whitespace is trimmed and tool casing is canonicalized" {
            val response = """  {"intention": "  Read the file.  ", "tool": "  files__readfile  "}  """

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Read the file." to "FILES__ReadFile")
        }

        "parseIntentionAndTool — unknown extra properties are ignored" {
            val response = """{"intention": "With extras.", "confidence": 0.9, "tool": "Answer"}"""

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("With extras." to "Answer")
        }

        "parseIntentionAndTool — unknown tool name throws UnknownTool with tool name and response" {
            val response = decision("Trying a non-existent tool.", "UNKNOWN__Tool")

            val ex = shouldThrow<AgentIntentionGenerationException.UnknownTool> {
                makeGenerator().parseIntentionAndTool(response, validTools)
            }
            ex.toolName shouldBe "UNKNOWN__Tool"
            ex.response shouldBe response
        }

        "parseIntentionAndTool — raw line breaks inside the intention string are tolerated" {
            // A real line feed inside a JSON string is invalid strict JSON, but a multi-line rationale is expected.
            val response = "{\"intention\": \"Step 1.\nStep 2.\", \"tool\": \"FILES__ReadFile\"}"

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Step 1.\nStep 2." to "FILES__ReadFile")
        }

        "parseIntentionAndTool — the last of several complete objects is selected" {
            val response = "${decision("First intention.", "Answer")}\n${decision("Second intention.", "FILES__ReadFile")}"

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Second intention." to "FILES__ReadFile")
        }

        "parseIntentionAndTool — a quoted format example before the decision is skipped" {
            val response =
                """
                Wait, the output format is:
                ${decision("...", "Answer")}
                More reasoning.
                ${decision("Read the file first.", "FILES__ReadFile")}
                """.trimIndent()

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Read the file first." to "FILES__ReadFile")
        }

        "parseIntentionAndTool — an object quoted inside the intention does not replace the outer decision" {
            val quoted = decision("Read.", "FILES__ReadFile")
            val response = decision("Example: $quoted Instead, answer.", "Answer")

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Example: $quoted Instead, answer." to "Answer")
        }

        "parseIntentionAndTool — an unfinished attempt followed by a complete decision is recovered" {
            val response = "{\"intention\": \"Long unfinished reasoning\n${decision("The final decision.", "Answer")}"

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("The final decision." to "Answer")
        }

        "parseIntentionAndTool — stray braces in prose do not hide the decision" {
            val response = "Set {x} aside. ${decision("After stray braces.", "Answer")} } {"

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("After stray braces." to "Answer")
        }

        "parseIntentionAndTool — an incomplete trailing object does not replace the last complete one" {
            val response = "${decision("Complete decision.", "Answer")}\n{\"intention\": \"Unfinished decision.\", \"tool\": "

            makeGenerator().parseIntentionAndTool(response, validTools) shouldBe ("Complete decision." to "Answer")
        }

        "parseIntentionAndTool — an unknown tool in the last object does not fall back to an earlier object" {
            val response = "${decision("Earlier example.", "Answer")}\n${decision("Actual decision.", "UNKNOWN__Tool")}"

            val ex = shouldThrow<AgentIntentionGenerationException.UnknownTool> {
                makeGenerator().parseIntentionAndTool(response, validTools)
            }
            ex.toolName shouldBe "UNKNOWN__Tool"
            ex.response shouldBe response
        }

        val invalidCases = listOf(
            "missing tool field" to """{"intention": "No tool field present."}""",
            "missing intention field" to """{"tool": "Answer"}""",
            "blank intention" to """{"intention": " \n ", "tool": "Answer"}""",
            "null tool" to """{"intention": "Decision.", "tool": null}""",
            "non-string intention" to """{"intention": ["Decision."], "tool": "Answer"}""",
            "truncated JSON" to """{"intention": "Decision.", "tool": "Ans""",
            "prose instead of JSON" to "I think we should answer the user.",
            "fields split across two objects" to """{"intention": "Decision."} Unrelated text {"tool": "Answer"}""",
            "legacy XML output" to "<intention>Decision.</intention><tool>Answer</tool>",
            "a blank response" to " \n\t ",
            "an empty response" to "",
        )

        invalidCases.forEach { (description, response) ->
            "parseIntentionAndTool — $description is rejected with InvalidFormat and the raw response" {
                val ex = shouldThrow<AgentIntentionGenerationException.InvalidFormat> {
                    makeGenerator().parseIntentionAndTool(response, validTools)
                }
                ex.response shouldBe response
            }
        }

        // -------------------------------------------------------------------------
        // executionState — user interaction branches
        // -------------------------------------------------------------------------

        "generate — executionState reflects user MessageEvent posted after last tool call" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns decision("Handling user follow-up.", "Answer")

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
            } returns decision("Processing user answer.", "Answer")

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
            } returns decision("Continuing after successful tool call.", "Answer")

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
                    "This is a malformed response with no JSON object at all",
                    decision("All good on retry.", "Answer"),
                )

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = makeGenerator().generate(
                "agent",
                makeContext(mockChatClient),
                makeInitialEvents(namespaceId, caseId),
                namespaceId,
                caseId,
            )

            result.toolName shouldBe "Answer"
            result.intention shouldContain "All good on retry"
            result.isFailedIntention shouldBe false
            prompts.size shouldBe 2
            prompts.last().contents shouldContain "This does not match the expected JSON output ($EXPECTED_FORMAT_MESSAGE)"
            prompts.last().contents shouldContain "output only the valid JSON object"
        }

        "generate retries on unknown tool and succeeds on second attempt" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val prompts = mutableListOf<Prompt>()
            every {
                mockChatClient.prompt(capture(prompts)).call().content()
            } returnsMany listOf(
                decision("Trying unknown tool.", "UNKNOWN__Tool"),
                decision("Recovered on retry.", "Answer"),
            )

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = makeGenerator().generate(
                "agent",
                makeContext(mockChatClient),
                makeInitialEvents(namespaceId, caseId),
                namespaceId,
                caseId,
            )

            result.toolName shouldBe "Answer"
            result.intention shouldContain "Recovered on retry"
            result.isFailedIntention shouldBe false
            prompts.last().contents shouldContain "the tool 'UNKNOWN__Tool' does not exist"
            prompts.last().contents shouldContain "Valid tools are: Answer"
        }

        "generate falls back to Answer after all retry attempts exhausted with meaningful intention" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            every {
                mockChatClient.prompt(any<Prompt>()).call().content()
            } returns "This is always malformed with no JSON object"

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val result = makeGenerator().generate(
                "agent",
                makeContext(mockChatClient),
                makeInitialEvents(namespaceId, caseId),
                namespaceId,
                caseId,
            )

            result.toolName shouldBe "Answer"
            result.intention shouldContain "Failed to plan next step after"
            result.intention shouldContain EXPECTED_FORMAT_MESSAGE
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
            } returns decision("Redirect after task.", "Answer")

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
            } returns decision("No redirect needed.", "Answer")

            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            makeGenerator().generate("agent", makeContext(mockChatClient), makeInitialEvents(namespaceId, caseId), namespaceId, caseId)

            // The reference to <redirect_guideline> in the Agent Handoff reasoning step is conditional too,
            // so no occurrence of "redirect_guideline" (tag or title) may leak into the prompt.
            val contents = promptSlot.captured.contents
            contents shouldNotContain "### Redirect Guideline"
            contents shouldNotContain "redirect_guideline"
        }

        "generate — redirectGuideline blank: prompt does not contain the guideline block" {
            val mockChatClient = mockk<ChatClient>(relaxed = true)
            val promptSlot = slot<Prompt>()
            every {
                mockChatClient.prompt(capture(promptSlot)).call().content()
            } returns decision("No redirect needed.", "Answer")

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
