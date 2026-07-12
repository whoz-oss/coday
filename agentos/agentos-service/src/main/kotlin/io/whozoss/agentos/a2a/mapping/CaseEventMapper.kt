package io.whozoss.agentos.a2a.mapping

import io.whozoss.agentos.a2a.dto.A2AArtifact
import io.whozoss.agentos.a2a.dto.A2AMessage
import io.whozoss.agentos.a2a.dto.A2APart
import io.whozoss.agentos.a2a.dto.A2ATaskState
import io.whozoss.agentos.a2a.dto.A2ATaskStatus
import io.whozoss.agentos.a2a.dto.TaskArtifactUpdateEvent
import io.whozoss.agentos.a2a.dto.TaskStatusUpdateEvent
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus

/**
 * Maps AgentOS [CaseEvent]s to A2A streaming events (spec §4.2).
 *
 * Prototype limitations — see docs/a2a.md:
 * - Agent output (MessageEvent with role=AGENT) is exposed as a
 *   [TaskArtifactUpdateEvent] rather than an A2A "agent message" (spec allows both;
 *   using artifacts is simpler for a chat-style single-turn output).
 * - Tool activity (ToolRequestEvent / ToolResponseEvent / ThinkingEvent) is
 *   intentionally NOT surfaced — A2A spec treats agent execution as opaque (§1.2).
 *   These events remain observable via AgentOS's native `/api/cases/{id}/events`.
 * - QuestionEvent produces an `input-required` status update carrying the question
 *   text as an `agent` message. The A2A client is expected to send a
 *   follow-up `message/send` on the same taskId to answer.
 */
object CaseEventMapper {
    /**
     * Convert a [CaseEvent] to one or more A2A streaming events.
     *
     * Returns an empty list when the event has no A2A representation (e.g. tool
     * activity or purely internal signals).
     *
     * [taskId] and [contextId] are stringified UUIDs; the caller resolves them
     * (typically `case.id.toString()`).
     */
    fun toA2AEvents(
        event: CaseEvent,
        taskId: String,
        contextId: String,
    ): List<Any> =
        when (event) {
            is CaseStatusEvent -> caseStatusEvent(event, taskId, contextId)
            is MessageEvent -> messageEvent(event, taskId, contextId)
            is AgentRunningEvent -> listOf(
                TaskStatusUpdateEvent(
                    taskId = taskId,
                    contextId = contextId,
                    status = A2ATaskStatus(
                        state = A2ATaskState.WORKING,
                        timestamp = event.timestamp.toString(),
                    ),
                    final = false,
                ),
            )
            is AgentFinishedEvent -> emptyList() // status change is emitted separately by CaseStatusEvent(IDLE)
            is QuestionEvent -> questionEvent(event, taskId, contextId)
            is ErrorEvent -> listOf(
                TaskStatusUpdateEvent(
                    taskId = taskId,
                    contextId = contextId,
                    status = A2ATaskStatus(
                        state = A2ATaskState.FAILED,
                        message = agentTextMessage(event.message, taskId, contextId, event.id.toString()),
                        timestamp = event.timestamp.toString(),
                    ),
                    final = true,
                ),
            )
            else -> emptyList()
        }

    /**
     * Map [CaseStatus] to [A2ATaskState] (prototype heuristic).
     *
     * Note: IDLE means "agent turn ended, awaiting next user message" in AgentOS
     * — which fits A2A's `input-required`. This is imperfect: an agent that
     * finished its work entirely also lands in IDLE. A proper mapping would
     * require an explicit "done" signal from the agent runtime. See docs/a2a.md.
     */
    fun mapStatus(status: CaseStatus): A2ATaskState =
        when (status) {
            CaseStatus.PENDING -> A2ATaskState.SUBMITTED
            CaseStatus.RUNNING -> A2ATaskState.WORKING
            CaseStatus.IDLE -> A2ATaskState.INPUT_REQUIRED
            CaseStatus.KILLED -> A2ATaskState.CANCELED
            CaseStatus.ERROR -> A2ATaskState.FAILED
        }

    /**
     * Build an [A2ATaskStatus] snapshot for a persisted [Case].
     */
    fun buildTaskStatus(case: Case): A2ATaskStatus =
        A2ATaskStatus(
            state = mapStatus(case.status),
            timestamp = case.metadata.modified.toString(),
        )

    // ---------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------

    private fun caseStatusEvent(
        event: CaseStatusEvent,
        taskId: String,
        contextId: String,
    ): List<TaskStatusUpdateEvent> {
        val state = mapStatus(event.status)
        return listOf(
            TaskStatusUpdateEvent(
                taskId = taskId,
                contextId = contextId,
                status = A2ATaskStatus(state = state, timestamp = event.timestamp.toString()),
                // "final" is true only for genuinely terminal states from the A2A
                // perspective. INPUT_REQUIRED closes the stream too (client will
                // reconnect on next turn) — this matches the spec §3.5.2.
                final = state.isTerminal() || state == A2ATaskState.INPUT_REQUIRED,
            ),
        )
    }

    private fun messageEvent(
        event: MessageEvent,
        taskId: String,
        contextId: String,
    ): List<Any> {
        // Only surface AGENT output. USER messages coming in via A2A are already
        // known to the client (they sent them). SYSTEM messages are internal.
        if (event.actor.role != ActorRole.AGENT) return emptyList()

        val parts = event.content.map { it.toA2APart() }
        val artifact = A2AArtifact(
            artifactId = event.id.toString(),
            name = "response",
            parts = parts,
        )
        return listOf(
            TaskArtifactUpdateEvent(
                taskId = taskId,
                contextId = contextId,
                artifact = artifact,
                append = false,
                lastChunk = true,
            ),
        )
    }

    private fun questionEvent(
        event: QuestionEvent,
        taskId: String,
        contextId: String,
    ): List<TaskStatusUpdateEvent> =
        listOf(
            TaskStatusUpdateEvent(
                taskId = taskId,
                contextId = contextId,
                status = A2ATaskStatus(
                    state = A2ATaskState.INPUT_REQUIRED,
                    message = agentTextMessage(event.question, taskId, contextId, event.id.toString()),
                    timestamp = event.timestamp.toString(),
                ),
                final = true,
            ),
        )

    private fun agentTextMessage(
        text: String,
        taskId: String,
        contextId: String,
        messageId: String,
    ): A2AMessage =
        A2AMessage(
            role = "agent",
            parts = listOf(A2APart.TextPart(text = text)),
            messageId = messageId,
            taskId = taskId,
            contextId = contextId,
        )

    private fun MessageContent.toA2APart(): A2APart =
        when (this) {
            is MessageContent.Text -> A2APart.TextPart(text = content)
            // Image parts get a placeholder text representation for the prototype.
            // A proper implementation would emit a FilePart with the base64 bytes.
            is MessageContent.Image -> A2APart.TextPart(
                text = "[image ${mimeType}, ${content.length} b64 chars]",
                metadata = mapOf("mimeType" to mimeType, "width" to width, "height" to height),
            )
        }
}
