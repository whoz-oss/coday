package io.whozoss.agentos.sdk.caseEvent

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * Type identifier for case events.
 * Values match the simple class names of each CaseEvent subtype,
 * used as the Jackson discriminant for polymorphic (de)serialization.
 */
enum class CaseEventType(
    @JsonValue val value: String,
) {
    STATUS("CaseStatusEvent"),
    AGENT_SELECTED("AgentSelectedEvent"),
    MESSAGE("MessageEvent"),
    TOOL_REQUEST("ToolRequestEvent"),
    TOOL_RESPONSE("ToolResponseEvent"),
    THINKING("ThinkingEvent"),
    AGENT_FINISHED("AgentFinishedEvent"),
    AGENT_RUNNING("AgentRunningEvent"),
    WARN("WarnEvent"),
    ERROR("ErrorEvent"),
    QUESTION("QuestionEvent"),
    ANSWER("AnswerEvent"),
    INTENTION_GENERATED("IntentionGeneratedEvent"),
    TOOL_SELECTED("ToolSelectedEvent"),
    TEXT_CHUNK("TextChunkEvent");

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): CaseEventType =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown CaseEventType value: $value")
    }
}

/**
 * Base interface for all case events.
 * Events are emitted during case execution to provide real-time updates.
 *
 * Implements Entity interface for standard CRUD operations.
 *
 * Jackson polymorphism: the `type` field (CaseEventType.value = class name)
 * is used as discriminant for serialization and deserialization.
 */
@JsonPropertyOrder(alphabetic = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = CaseStatusEvent::class, name = "CaseStatusEvent"),
    JsonSubTypes.Type(value = AgentSelectedEvent::class, name = "AgentSelectedEvent"),
    JsonSubTypes.Type(value = MessageEvent::class, name = "MessageEvent"),
    JsonSubTypes.Type(value = ToolRequestEvent::class, name = "ToolRequestEvent"),
    JsonSubTypes.Type(value = ToolResponseEvent::class, name = "ToolResponseEvent"),
    JsonSubTypes.Type(value = ThinkingEvent::class, name = "ThinkingEvent"),
    JsonSubTypes.Type(value = AgentFinishedEvent::class, name = "AgentFinishedEvent"),
    JsonSubTypes.Type(value = AgentRunningEvent::class, name = "AgentRunningEvent"),
    JsonSubTypes.Type(value = WarnEvent::class, name = "WarnEvent"),
    JsonSubTypes.Type(value = QuestionEvent::class, name = "QuestionEvent"),
    JsonSubTypes.Type(value = AnswerEvent::class, name = "AnswerEvent"),
    JsonSubTypes.Type(value = IntentionGeneratedEvent::class, name = "IntentionGeneratedEvent"),
    JsonSubTypes.Type(value = ToolSelectedEvent::class, name = "ToolSelectedEvent"),
    JsonSubTypes.Type(value = TextChunkEvent::class, name = "TextChunkEvent"),
)
sealed interface CaseEvent : Entity {
    val projectId: UUID
    val caseId: UUID
    val timestamp: Instant
    val type: CaseEventType
}

/**
 * Emitted when the case status changes.
 */
data class CaseStatusEvent(
    override val metadata: EntityMetadata,
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val status: CaseStatus,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.STATUS
}

data class WarnEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
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
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_SELECTED
}

data class AgentFinishedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_FINISHED
}

data class AgentRunningEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
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
    override val metadata: EntityMetadata = EntityMetadata(),
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
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val args: String?,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_REQUEST
}

/**
 * Emitted when a tool execution completes.
 */
data class ToolResponseEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
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
    override val metadata: EntityMetadata = EntityMetadata(),
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
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
    val question: String,
    val options: List<String>? = null,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.QUESTION

    /**
     * Create an AnswerEvent that references this question.
     */
    fun createAnswer(actor: Actor, answer: String): AnswerEvent =
        AnswerEvent(
            projectId = projectId,
            caseId = caseId,
            questionId = id,
            actor = actor,
            answer = answer,
        )
}

/**
 * Emitted when a user responds to a QuestionEvent.
 * References the original question via questionId.
 */
data class AnswerEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val questionId: UUID,
    val actor: Actor,
    val answer: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.ANSWER
}

/**
 * Emitted when an agent generates an intention for the next step.
 * Used for observability and potential resumption of interrupted runs.
 */
data class IntentionGeneratedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val intention: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.INTENTION_GENERATED
}

/**
 * Emitted when an agent selects a tool to execute.
 * Used for observability and debugging.
 */
data class ToolSelectedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val toolName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_SELECTED
}

/**
 * Emitted during streaming text generation.
 * Allows progressive display of agent responses.
 */
data class TextChunkEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val projectId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val chunk: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TEXT_CHUNK
}
