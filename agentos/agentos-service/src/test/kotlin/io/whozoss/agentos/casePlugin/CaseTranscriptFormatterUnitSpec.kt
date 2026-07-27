package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.ToolSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import java.time.Instant
import java.util.UUID

class CaseTranscriptFormatterUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()
    val agentId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER)
    val agentActor = Actor(id = "agent-1", displayName = "BotAgent", role = ActorRole.AGENT)
    val t0: Instant = Instant.parse("2025-01-15T14:23:00Z")

    fun userMessage(text: String, offset: Long = 0) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        timestamp = t0.plusSeconds(offset),
        actor = userActor,
        content = listOf(MessageContent.Text(text)),
    )

    fun agentMessage(text: String, offset: Long = 1) = MessageEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        timestamp = t0.plusSeconds(offset),
        actor = agentActor,
        content = listOf(MessageContent.Text(text)),
    )

    // -------------------------------------------------------------------------
    // Conversational-only mode (includesTechnicalEvents = false)
    // -------------------------------------------------------------------------

    "conversational mode: includes MessageEvent for USER" {
        val events = listOf(userMessage("Hello"))
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "USER: Hello"
    }

    "conversational mode: includes MessageEvent for AGENT" {
        val events = listOf(agentMessage("Hi there"))
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "AGENT: Hi there"
    }

    "conversational mode: includes WarnEvent" {
        val events = listOf(
            WarnEvent(namespaceId = namespaceId, caseId = caseId, timestamp = t0, message = "watch out"),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "WARN: watch out"
    }

    "conversational mode: includes ErrorEvent" {
        val events = listOf(
            ErrorEvent(namespaceId = namespaceId, caseId = caseId, timestamp = t0, message = "boom"),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "ERROR: boom"
    }

    "conversational mode: includes QuestionEvent with options" {
        val events = listOf(
            QuestionEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                agentName = "Analyst",
                question = "Which file?",
                options = listOf("a.kt", "b.kt"),
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "QUESTION (Analyst): Which file? [options: a.kt, b.kt]"
    }

    "conversational mode: includes QuestionEvent without options" {
        val events = listOf(
            QuestionEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                agentName = "Analyst",
                question = "What is your name?",
                options = null,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "QUESTION (Analyst): What is your name?"
        result shouldNotContain "[options"
    }

    "conversational mode: includes AnswerEvent" {
        val questionId = UUID.randomUUID()
        val events = listOf(
            AnswerEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                questionId = questionId,
                actor = userActor,
                answer = "a.kt",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "ANSWER: a.kt"
    }

    "conversational mode: excludes AgentSelectedEvent" {
        val events = listOf(
            AgentSelectedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                agentName = "Coder",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldNotContain "AGENT_SELECTED"
    }

    "conversational mode: excludes ToolRequestEvent" {
        val events = listOf(
            ToolRequestEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "FILES__readFile",
                args = "{}",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldNotContain "TOOL_REQUEST"
    }

    "conversational mode: excludes CaseStatusEvent" {
        val events = listOf(
            CaseStatusEvent(
                metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                status = CaseStatus.IDLE,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldNotContain "STATUS"
    }

    // -------------------------------------------------------------------------
    // Full mode (includesTechnicalEvents = true)
    // -------------------------------------------------------------------------

    "full mode: includes AgentSelectedEvent" {
        val events = listOf(
            AgentSelectedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                agentName = "Coder",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "AGENT_SELECTED: Coder"
    }

    "full mode: includes AgentRunningEvent" {
        val events = listOf(
            AgentRunningEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                agentName = "Coder",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "AGENT_RUNNING: Coder"
    }

    "full mode: includes AgentFinishedEvent" {
        val events = listOf(
            AgentFinishedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                agentName = "Coder",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "AGENT_FINISHED: Coder"
    }

    "full mode: includes CaseStatusEvent" {
        val events = listOf(
            CaseStatusEvent(
                metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(),
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                status = CaseStatus.IDLE,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "STATUS: IDLE"
    }

    "full mode: includes ToolRequestEvent" {
        val events = listOf(
            ToolRequestEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "FILES__readFile",
                args = "{\"filePath\":\"project://src/main.kt\"}",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "TOOL_REQUEST: FILES__readFile"
    }

    "full mode: includes ToolResponseEvent with success and duration" {
        val events = listOf(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "FILES__readFile",
                output = MessageContent.Text("file content here"),
                success = true,
                durationMs = 42,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "TOOL_RESPONSE: [success, 42ms] file content here"
    }

    "full mode: includes ToolResponseEvent with error status" {
        val events = listOf(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "FILES__readFile",
                output = MessageContent.Text("not found"),
                success = false,
                durationMs = null,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "TOOL_RESPONSE: [error, ?ms] not found"
    }

    "full mode: ToolResponseEvent with images renders a count, not the base64" {
        val events = listOf(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "FILES__readAsImage",
                output = MessageContent.Text("Rendered PDF cv.pdf: page(s) 1-2 of 2"),
                success = true,
                durationMs = 42,
                images = listOf(
                    MessageContent.Image(content = "aGVsbG8=", mimeType = "image/jpeg", width = 800, height = 600),
                    MessageContent.Image(content = "d29ybGQ=", mimeType = "image/jpeg", width = 800, height = 600),
                ),
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "TOOL_RESPONSE: [success, 42ms] Rendered PDF cv.pdf: page(s) 1-2 of 2 [+2 image(s)]"
        result shouldNotContain "aGVsbG8="
    }

    "full mode: includes IntentionGeneratedEvent" {
        val events = listOf(
            IntentionGeneratedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                intention = "read the config file",
                toolName = "FILES__readFile",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "INTENTION ($agentId): read the config file → FILES__readFile"
    }

    "full mode: includes ToolSelectedEvent" {
        val events = listOf(
            ToolSelectedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                agentId = agentId,
                toolName = "FILES__readFile",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "TOOL_SELECTED ($agentId): FILES__readFile"
    }

    "full mode: includes PendingConfirmationEvent" {
        val events = listOf(
            PendingConfirmationEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "FILES__remove",
                inputJson = "{\"path\":\"project://old.txt\"}",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "PENDING_CONFIRMATION: FILES__remove"
    }

    "full mode: includes ConfirmationResolvedEvent" {
        val events = listOf(
            ConfirmationResolvedEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                pendingEventId = UUID.randomUUID(),
                confirmed = true,
                resultText = "deleted",
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "CONFIRMATION_RESOLVED: confirmed=true deleted"
    }

    // -------------------------------------------------------------------------
    // ToolResponseEvent truncation
    // -------------------------------------------------------------------------

    "truncates ToolResponseEvent output beyond 2000 chars" {
        val longText = "x".repeat(2500)
        val events = listOf(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "BigTool",
                output = MessageContent.Text(longText),
                success = true,
                durationMs = 10,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldContain "... [truncated]"
        // The rendered output section must be at most 2000 + suffix length
        val outputPart = result.substringAfter("[success, 10ms] ")
        outputPart.length shouldBe 2000 + "... [truncated]".length
    }

    "does not truncate ToolResponseEvent output at exactly 2000 chars" {
        val exactText = "y".repeat(2000)
        val events = listOf(
            ToolResponseEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                toolRequestId = "req-1",
                toolName = "BigTool",
                output = MessageContent.Text(exactText),
                success = true,
                durationMs = 5,
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = true)
        result shouldNotContain "... [truncated]"
    }

    // -------------------------------------------------------------------------
    // MessageContent.Image
    // -------------------------------------------------------------------------

    "renders MessageContent.Image as [image]" {
        val events = listOf(
            MessageEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                timestamp = t0,
                actor = userActor,
                content = listOf(
                    MessageContent.Text("look at this: "),
                    MessageContent.Image(content = "base64data", mimeType = "image/png"),
                ),
            ),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        result shouldContain "USER: look at this:  [image]"
    }

    // -------------------------------------------------------------------------
    // Chronological ordering (formatter preserves input order)
    // -------------------------------------------------------------------------

    "preserves chronological order of events" {
        val events = listOf(
            userMessage("first", offset = 0),
            agentMessage("second", offset = 1),
            userMessage("third", offset = 2),
        )
        val result = CaseTranscriptFormatter.format(events, includesTechnicalEvents = false)
        val lines = result.lines()
        lines[0] shouldContain "first"
        lines[1] shouldContain "second"
        lines[2] shouldContain "third"
    }

    "empty event list produces empty string" {
        val result = CaseTranscriptFormatter.format(emptyList(), includesTechnicalEvents = true)
        result shouldBe ""
    }
})
