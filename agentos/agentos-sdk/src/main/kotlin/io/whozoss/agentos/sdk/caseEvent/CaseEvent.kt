package io.whozoss.agentos.sdk.caseEvent

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
    TEXT_CHUNK("TextChunkEvent"),
    PENDING_CONFIRMATION("PendingConfirmationEvent"),
    CONFIRMATION_RESOLVED("ConfirmationResolvedEvent"),
    ;

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
    JsonSubTypes.Type(value = PendingConfirmationEvent::class, name = "PendingConfirmationEvent"),
    JsonSubTypes.Type(value = ConfirmationResolvedEvent::class, name = "ConfirmationResolvedEvent"),
)
sealed interface CaseEvent : Entity {
    val namespaceId: UUID
    val caseId: UUID
    val timestamp: Instant
    val type: CaseEventType
}

/**
 * Marker interface for [CaseEvent] subtypes that must NOT be persisted.
 *
 * Transient events are emitted on the SSE flow for real-time display but carry
 * no replay value — they are superseded by the durable events that follow them
 * (e.g. [TextChunkEvent]s are superseded by the final [MessageEvent]).
 *
 * Rules for transient events:
 * - They reach the SSE flow unconditionally.
 * - They are never written to the event store (neither in-memory nor Neo4j).
 * - They are never pushed into the runtime's in-memory event list.
 *
 * Plugin authors may mark their own event subtypes as transient by implementing
 * this interface alongside [CaseEvent].
 */
interface TransientCaseEvent

/**
 * Emitted when the case status changes.
 */
data class CaseStatusEvent(
    override val metadata: EntityMetadata,
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val status: CaseStatus,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.STATUS
}

data class WarnEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
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
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_SELECTED
}

data class AgentFinishedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val agentName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.AGENT_FINISHED
}

data class AgentRunningEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
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
    override val namespaceId: UUID,
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
    override val namespaceId: UUID,
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
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val output: MessageContent,
    val success: Boolean = true,
    /** Wall-clock duration of the tool execution in milliseconds, null when not measured. */
    val durationMs: Long? = null,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.TOOL_RESPONSE
}

/**
 * Emitted to indicate the case is thinking/processing.
 * Transient: streamed live but not persisted.
 */
data class ThinkingEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
) : CaseEvent, TransientCaseEvent {
    override val type: CaseEventType = CaseEventType.THINKING
}

/**
 * Emitted when an agent asks a question to the user via a tool.
 * The user can respond asynchronously via an AnswerEvent.
 */
data class QuestionEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
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
    fun createAnswer(
        actor: Actor,
        answer: String,
    ): AnswerEvent =
        AnswerEvent(
            namespaceId = namespaceId,
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
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val questionId: UUID,
    val actor: Actor,
    val answer: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.ANSWER
}

/**
 * Emitted when an agent generates an intention for the next step and selects the tool to call.
 * Replaces the separate ToolSelectedEvent in the advanced execution flow — both are produced
 * by a single LLM call so they are always consistent.
 * Used for observability and potential resumption of interrupted runs.
 */
data class IntentionGeneratedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val agentId: UUID,
    val intention: String,
    /** Name of the tool selected by the LLM in the same call that produced [intention]. */
    val toolName: String,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.INTENTION_GENERATED
}

/**
 * Emitted when an agent selects a tool to execute.
 * Used for observability and debugging.
 */
data class ToolSelectedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
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
 * Transient: streamed live but not persisted.
 */
data class TextChunkEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val chunk: String,
) : CaseEvent, TransientCaseEvent {
    override val type: CaseEventType = CaseEventType.TEXT_CHUNK
}

/**
 * Emitted when a tool execution was deferred awaiting explicit user confirmation.
 *
 * Carries the payload [getConfirmationPayload] computed and a sanitized human-readable
 * label. The pairing with [ConfirmationResolvedEvent] (matched on
 * [ConfirmationResolvedEvent.pendingEventId]) is what `AgentSimple` uses to detect
 * unresolved confirmations on case re-entry — including after a server restart
 * (WZ-31596 CA6).
 *
 * @param toolRequestId The id of the [ToolRequestEvent] that triggered this pending
 *   (kept for traceability with the LLM-visible tool-call cycle).
 * @param toolName The qualified tool name (e.g. `FILES__remove`).
 * @param pendingPayloadJson The payload, serialized as JSON. Stored as a String to
 *   stay classloader-safe across plugin/service boundaries (no `Class.forName`).
 *   The owning tool re-converts to its typed payload inside its plugin classloader.
 * @param confirmationLabel A short, sanitized label suitable for inclusion in an
 *   LLM prompt and a UI message (whitelist + 200-char cap applied at emission).
 * @param analysisInstructions Optional tool-supplied instructions appended to the
 *   `analyzeConfirmation` prompt.
 */
data class PendingConfirmationEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val toolRequestId: String,
    val toolName: String,
    val pendingPayloadJson: String,
    val confirmationLabel: String,
    val analysisInstructions: String = "",
    /**
     * WZ-31596: id of the paired [QuestionEvent] used to prompt the user out-of-LLM-channel.
     * Nullable for backward compat with legacy pendings created without a QuestionEvent.
     */
    val questionId: UUID? = null,
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.PENDING_CONFIRMATION
}

/**
 * Marker that closes a [PendingConfirmationEvent].
 *
 * Carries the resolution outcome and a back-reference to the pending event id, so the
 * orchestrator can detect "all confirmations for this case are resolved" without
 * scanning tool-call ids.
 *
 * Note (WZ-31596 F4): the [ToolResponseEvent] emitted post-resolution and paired on
 * the originating [PendingConfirmationEvent.toolRequestId] is a synthetic re-emission.
 * The original [ToolRequestEvent] never produced a native tool response at its turn;
 * this synthetic response exists solely to feed `lastToolResponse` for
 * `AgentIntentionGenerator` on the next intention turn. Audit / replay consumers that
 * pair `ToolRequestEvent ↔ ToolResponseEvent` via `toolRequestId` will see an
 * artificially coherent pair — the actual side-effect happened during confirmation
 * resolution, not at the original tool-call turn.
 */
data class ConfirmationResolvedEvent(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID,
    override val caseId: UUID,
    override val timestamp: Instant = Instant.now(),
    val pendingEventId: UUID,
    val confirmed: Boolean,
    /**
     * WZ-31596: textual result of [StandardTool.executeWithConfirmation] (when confirmed)
     * or [StandardTool.onRejected] (when rejected). Stored on the marker so that
     * `convertEventsToMessages` can inject a synthetic tool_result for the LLM without
     * having to re-execute the tool.
     */
    val resultText: String = "",
) : CaseEvent {
    override val type: CaseEventType = CaseEventType.CONFIRMATION_RESOLVED
}
