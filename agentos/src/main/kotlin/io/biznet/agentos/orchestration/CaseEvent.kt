package io.biznet.agentos.orchestration

import java.time.Instant
import java.util.UUID

/**
 * Type identifier for case events.
 * Used for indexation and deserialization.
 */
enum class CaseEventType(
    val value: String,
) {
    STATUS("status"),
    AGENT_SELECTED("agent_selected"),
    MESSAGE("message"),
    TOOL_REQUEST("tool_request"),
    TOOL_RESPONSE("tool_response"),
    THINKING("thinking"),
    AGENT_FINISHED("agent_finished"),
    AGENT_RUNNING("agent_running"),
    WARN("warning"),
    ERROR("error"),
    QUESTION("question"),
    ANSWER("answer"),
}

/**
 * Base interface for all case events.
 * Events are emitted during case execution to provide real-time updates.
 */
sealed interface CaseEvent {
    val id: UUID
    val projectId: UUID
    val caseId: UUID
    val timestamp: Instant
    val type: CaseEventType
}

/**
 * Emitted when the case status changes.
 */
data class CaseStatusEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val status: CaseStatus,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.STATUS
}

data class WarnEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val message: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.WARN
}

/**
 * Emitted when an agent is selected to process the case.
 */
data class AgentSelectedEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_SELECTED
}

// todo: consider an AgentStatusEvent with SELECTED, FINISHED, ERROR ?
data class AgentFinishedEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_FINISHED
}

data class AgentRunningEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_RUNNING
}

/**
 * Emitted when a message is added to the context.
 */
data class MessageEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val actor: Actor,
    val content: List<MessageContent>,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.MESSAGE
}

/**
 * Emitted when a tool is requested.
 */
data class ToolRequestEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val args: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_REQUEST
}

/**
 * Emitted when a tool execution completes.
 */
data class ToolResponseEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val output: MessageContent,
    val success: Boolean = true,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_RESPONSE
}

/**
 * Emitted to indicate the case is thinking/processing.
 */
data class ThinkingEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.THINKING
}

/**
 * Emitted when an agent asks a question to the user via a tool.
 * The user can respond asynchronously via an AnswerEvent.
 */
data class QuestionEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
    val question: String,
    val options: List<String>? = null, // Optional choices for the user
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.QUESTION

    /**
     * Create an AnswerEvent that references this question.
     */
    fun createAnswer(
        actor: Actor,
        answer: String,
    ): AnswerEvent {
        return AnswerEvent(
            projectId = projectId,
            caseId = caseId,
            questionId = id,
            actor = actor,
            answer = answer,
        )
    }
}

/**
 * Emitted when a user responds to a QuestionEvent.
 * References the original question via questionId.
 */
data class AnswerEvent(
    override val id: UUID = UUID.randomUUID(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val questionId: UUID,
    val actor: Actor,
    val answer: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.ANSWER
}
