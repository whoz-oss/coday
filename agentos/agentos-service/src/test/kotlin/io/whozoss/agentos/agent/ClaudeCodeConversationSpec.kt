package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import java.util.UUID

class ClaudeCodeConversationSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        fun user(
            text: String,
            sessionContext: Map<String, Any>? = null,
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor("user-1", "Selim", ActorRole.USER),
            content = listOf(MessageContent.Text(text)),
            sessionContext = sessionContext,
        )

        fun agent(
            text: String,
            id: UUID = agentId,
            name: String = "dev",
        ) = MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            actor = Actor(id.toString(), name, ActorRole.AGENT),
            content = listOf(MessageContent.Text(text)),
        )

        fun finished(
            runtimeSessionId: String? = sessionId.toString(),
            id: UUID = agentId,
        ) = AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = id, agentName = "dev", runtimeSessionId = runtimeSessionId)

        fun conversation(vararg events: CaseEvent) = ClaudeCodeConversation(events.toList(), agentId)

        "a first run starts a session with the message alone" {
            val conversation = conversation(user("Hello"))

            conversation.resumableSessionId.shouldBeNull()
            conversation.newSessionMessage() shouldBe """<user name="Selim">Hello</user>"""
        }

        "a run after a completed run resumes its session with only what happened since" {
            val conversation =
                conversation(
                    user("First question"),
                    agent("First answer"),
                    finished(),
                    agent("I disagree", id = UUID.randomUUID(), name = "reviewer"),
                    user("Second question"),
                )

            conversation.resumableSessionId shouldBe sessionId
            conversation.resumeMessage() shouldBe
                """
                <agent name="reviewer">I disagree</agent>

                <user name="Selim">Second question</user>
                """.trimIndent()
        }

        "a run after a run without session starts over with the case as recovered history" {
            val message =
                conversation(
                    user("First question"),
                    agent("First answer"),
                    finished(runtimeSessionId = null),
                    user("Second question"),
                ).newSessionMessage()

            message shouldStartWith "[Conversation so far in this case"
            message shouldContain """<user name="Selim">First question</user>"""
            message shouldContain "<you>First answer</you>"
            message shouldEndWith "[End of the recovered conversation. New messages:]\n\n<user name=\"Selim\">Second question</user>"
        }

        "only the agent's own last run decides the session" {
            val conversation =
                conversation(
                    user("Hello"),
                    finished(),
                    user("Ask the reviewer"),
                    finished(runtimeSessionId = null, id = UUID.randomUUID()),
                )

            conversation.resumableSessionId shouldBe sessionId
        }

        "the session context goes right before the last user message only" {
            val message =
                conversation(
                    user("Old", sessionContext = mapOf("page" to "old-page")),
                    user("New", sessionContext = mapOf("page" to "agent <x>")),
                ).newSessionMessage()

            message shouldNotContain "old-page"
            message shouldContain "<session-context>\n  page: agent &lt;x&gt;\n</session-context>\n<user name=\"Selim\">New</user>"
        }

        "the answer to the agent's question is resumed without repeating the question" {
            val questionId = UUID.randomUUID()
            val conversation =
                conversation(
                    user("Deploy it"),
                    finished(),
                    QuestionEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = "dev", question = "Which branch?"),
                    AnswerEvent(namespaceId = namespaceId, caseId = caseId, questionId = questionId, actor = Actor("user-1", "Selim", ActorRole.USER), answer = "main"),
                )

            conversation.resumeMessage() shouldBe """<user name="Selim">main</user>"""
        }

        "recovered tool calls and results are kept short" {
            val message =
                conversation(
                    user("Read it"),
                    ToolRequestEvent(namespaceId = namespaceId, caseId = caseId, toolRequestId = "t1", toolName = "FILES__read", args = """{"path":"a"}"""),
                    ToolResponseEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        toolRequestId = "t1",
                        toolName = "FILES__read",
                        output = MessageContent.Text("x".repeat(5_000)),
                    ),
                    finished(runtimeSessionId = null),
                    user("Again"),
                ).newSessionMessage()

            message shouldContain """<tool-call name="FILES__read">{"path":"a"}</tool-call>"""
            message shouldContain """<tool-result name="FILES__read">${"x".repeat(ClaudeCodeConversation.MAX_TOOL_CHARS)}[…]</tool-result>"""
        }

        "a long recovered history keeps its most recent part" {
            val message =
                conversation(
                    user("start " + "a".repeat(ClaudeCodeConversation.MAX_HISTORY_CHARS)),
                    agent("latest answer"),
                    finished(runtimeSessionId = null),
                    user("Next"),
                ).newSessionMessage()

            message shouldNotContain "start "
            message shouldContain "[…]\n"
            message shouldContain "<you>latest answer</you>"
        }

        "nothing new since the last run asks the agent to continue" {
            conversation(user("Hello"), finished()).resumeMessage() shouldBe ClaudeCodeConversation.CONTINUE
        }
    })
