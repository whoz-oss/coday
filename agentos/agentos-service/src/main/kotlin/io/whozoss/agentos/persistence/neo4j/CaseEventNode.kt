package io.whozoss.agentos.persistence.neo4j

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Marker interface for all Spring Data Neo4j node projections of [io.whozoss.agentos.sdk.caseEvent.CaseEvent] subtypes.
 *
 * Each subtype has its own node class annotated with two labels: the primary
 * `:CaseEvent` label (for cross-subtype queries) and a secondary label matching
 * the subtype name (e.g. `:MessageEvent`). SDN uses the secondary label to
 * instantiate the correct node class when reading from the graph.
 *
 * Node classes are pure data holders — no mapping logic. All conversion between
 * domain objects and node classes is handled by [CaseEventNodeMapper].
 */
sealed interface CaseEventNode {
    val id: String
    val caseId: String
    val namespaceId: String
    val timestamp: Instant
    val created: Instant
    val createdBy: String?
    val modified: Instant
    val modifiedBy: String?
    val removed: Boolean?
}

@Node("CaseEvent", "CaseStatusEvent")
data class CaseStatusEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val status: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "WarnEvent")
data class WarnEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val message: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "AgentSelectedEvent")
data class AgentSelectedEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val agentId: String,
    val agentName: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "AgentFinishedEvent")
data class AgentFinishedEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val agentId: String,
    val agentName: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "AgentRunningEvent")
data class AgentRunningEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val agentId: String,
    val agentName: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "MessageEvent")
data class MessageEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val actorId: String,
    val actorDisplayName: String,
    val actorRole: String,
    /** JSON-serialised [List]<[io.whozoss.agentos.sdk.caseEvent.MessageContent]> */
    val contentJson: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "ToolRequestEvent")
data class ToolRequestEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val toolRequestId: String,
    val toolName: String,
    val args: String?,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "ToolResponseEvent")
data class ToolResponseEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val toolRequestId: String,
    val toolName: String,
    /** JSON-serialised [io.whozoss.agentos.sdk.caseEvent.MessageContent] */
    val outputJson: String,
    val success: Boolean = true,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "ThinkingEvent")
data class ThinkingEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "QuestionEvent")
data class QuestionEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val agentId: String,
    val agentName: String,
    val question: String,
    /** JSON-serialised [List]<[String]>?, null when no options */
    val options: String? = null,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "AnswerEvent")
data class AnswerEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val questionId: String,
    val actorId: String,
    val actorDisplayName: String,
    val actorRole: String,
    val answer: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "IntentionGeneratedEvent")
data class IntentionGeneratedEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val agentId: String,
    val intention: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "ToolSelectedEvent")
data class ToolSelectedEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val agentId: String,
    val toolName: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode

@Node("CaseEvent", "TextChunkEvent")
data class TextChunkEventNode(
    @Id override val id: String,
    override val caseId: String,
    override val namespaceId: String,
    override val timestamp: Instant,
    val chunk: String,
    override val created: Instant = Instant.now(),
    override val createdBy: String? = null,
    override val modified: Instant = Instant.now(),
    override val modifiedBy: String? = null,
    override val removed: Boolean? = null,
) : CaseEventNode
