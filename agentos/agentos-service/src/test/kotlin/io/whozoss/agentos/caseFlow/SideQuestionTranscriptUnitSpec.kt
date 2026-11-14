package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import java.util.UUID

class SideQuestionTranscriptUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()
        val user = Actor(id = UUID.randomUUID().toString(), displayName = "Alice", role = ActorRole.USER)
        val agent = Actor(id = UUID.randomUUID().toString(), displayName = "Dev", role = ActorRole.AGENT)

        fun message(
            text: String,
            actor: Actor = user,
        ): CaseEvent =
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                actor = actor,
                content = listOf(MessageContent.Text(text)),
            )

        fun render(
            vararg events: CaseEvent,
            maxChars: Int = SideQuestionTranscript.MAX_CHARS,
        ) = SideQuestionTranscript.render(events.toList(), maxChars)

        "an empty history renders a placeholder, so the prompt is never empty" {
            render() shouldBe SideQuestionTranscript.EMPTY
        }

        "renders user and agent messages in order, with who spoke" {
            val transcript = render(message("Fix the login bug"), message("On it, looking at AuthService", agent))

            transcript shouldBe "User Alice: Fix the login bug\n\nAgent Dev: On it, looking at AuthService"
        }

        "speaks of a bare User or Agent when the actor has no display name" {
            val nameless = Actor(id = "x", displayName = "", role = ActorRole.USER)

            render(message("hello", nameless)) shouldBe "User: hello"
        }

        "keeps only the text of a voice message, and skips a message without any" {
            val voice =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = user,
                    content =
                        listOf(
                            MessageContent.Audio(path = "voice-messages/v.webm", mimeType = "audio/webm"),
                            MessageContent.Text("spoken words"),
                        ),
                )
            val audioOnly = MessageEvent(namespaceId = namespaceId, caseId = caseId, actor = user, content = emptyList())

            val transcript = render(voice, audioOnly)

            transcript shouldBe "User Alice: spoken words"
            transcript shouldNotContain "voice-messages"
        }

        "renders a question of an agent with its options, and the answer" {
            val question =
                QuestionEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.randomUUID(),
                    agentName = "Dev",
                    question = "Which branch?",
                    options = listOf("main", "dev"),
                )
            val answer = question.createAnswer(user, "main")

            render(question, answer) shouldBe
                "Agent Dev asked the user: Which branch? (options: main, dev)\n\nUser Alice answered: main"
        }

        "summarizes a tool call as one line with its name only, never its arguments or its output" {
            val request =
                ToolRequestEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "t-1",
                    toolName = "FILES__readFile",
                    args = """{"path":"secret/passwords.txt"}""",
                )
            val response =
                ToolResponseEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    toolRequestId = "t-1",
                    toolName = "FILES__readFile",
                    output = MessageContent.Text("hunter2"),
                )

            val transcript = render(request, response)

            transcript shouldBe "[Tool call: FILES__readFile]"
            transcript shouldNotContain "passwords"
            transcript shouldNotContain "hunter2"
        }

        "skips the technical and transient events" {
            val transcript =
                render(
                    message("hello"),
                    CaseStatusEvent(
                        metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
                        namespaceId = namespaceId,
                        caseId = caseId,
                        status = CaseStatus.RUNNING,
                    ),
                    ThinkingEvent(namespaceId = namespaceId, caseId = caseId),
                    TextChunkEvent(namespaceId = namespaceId, caseId = caseId, chunk = "partial"),
                    WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "integration failed"),
                )

            transcript shouldBe "User Alice: hello"
        }

        "a history that ends on a dangling tool call and two consecutive user messages renders fine" {
            val transcript =
                render(
                    message("first"),
                    message("second, sent while the agent was working"),
                    ToolRequestEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "t-1",
                        toolName = "BASH__run",
                        args = null,
                    ),
                )

            transcript shouldBe
                "User Alice: first\n\nUser Alice: second, sent while the agent was working\n\n[Tool call: BASH__run]"
        }

        "within the budget, nothing is dropped and no marker is added" {
            val transcript = render(message("one"), message("two"), maxChars = 1_000)

            transcript shouldNotContain SideQuestionTranscript.OMITTED_MARKER
            transcript shouldContain "one"
        }

        "over the budget, keeps the tail: the oldest entries go, with a marker" {
            val events = (1..50).map { message("message-$it " + "x".repeat(50)) }.toTypedArray()

            val transcript = render(*events, maxChars = 500)

            transcript shouldStartWith SideQuestionTranscript.OMITTED_MARKER
            transcript shouldContain "message-50 "
            transcript shouldNotContain "message-1 "
            // The entries are kept whole, and the marker is the only thing added to the budget.
            transcript.removePrefix(SideQuestionTranscript.OMITTED_MARKER + "\n\n") shouldHaveMaxLength 500
        }

        "a single entry larger than the budget is cut, keeping its end" {
            val transcript = render(message("start-" + "y".repeat(1_000) + "-end"), maxChars = 100)

            transcript shouldStartWith SideQuestionTranscript.OMITTED_MARKER
            transcript shouldEndWith "-end"
            transcript shouldNotContain "start-"
        }
    })
