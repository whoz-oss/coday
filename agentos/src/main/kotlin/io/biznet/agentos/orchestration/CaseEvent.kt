package io.biznet.agentos.orchestration

import java.time.Instant
import java.util.UUID

/**
 * Type identifier for case events.
 * Used for indexation and deserialization.
 */
enum class CaseEventType(val value: String) {
    STATUS("status"),
    AGENT_SELECTED("agent_selected"),
    MESSAGE("message"),
    TOOL_REQUEST("tool_request"),
    TOOL_RESPONSE("tool_response"),
    THINKING("thinking")
}

/**
 * Base interface for all case events.
 * Events are emitted during case execution to provide real-time updates.
 */
sealed interface CaseEvent {
    val caseId: UUID
    val timestamp: Instant
    val type: CaseEventType
}

/**
 * Represents the lifecycle status transitions emitted as events.
 */
enum class CaseEventStatus {
    /** Case has started execution */
    STARTED,
    /** Case is in the process of completing (final steps in progress) */
    COMPLETING,
    /** Case has completed successfully */
    COMPLETED,
    /** Case is in the process of stopping */
    STOPPING,
    /** Case has been stopped (manually or by policy) */
    STOPPED,
    /** Case encountered an error */
    ERROR
}

/**
 * Emitted when the case status changes.
 */
data class CaseStatusEvent(
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val status: CaseEventStatus
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.STATUS
}

/**
 * Emitted when an agent is selected to process the case.
 */
data class AgentSelectedEvent(
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: String,
    val agentName: String
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_SELECTED
}

/**
 * Emitted when a message is added to the context.
 */
data class MessageEvent(
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val actor: Actor,
    val content: List<MessageContent>
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.MESSAGE
}

/**
 * Emitted when a tool is requested.
 */
data class ToolRequestEvent(
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val args: String
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_REQUEST
}

/**
 * Emitted when a tool execution completes.
 */
data class ToolResponseEvent(
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val output: MessageContent,
    val success: Boolean = true
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_RESPONSE
}

/**
 * Emitted to indicate the case is thinking/processing.
 */
data class ThinkingEvent(
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.THINKING
}
