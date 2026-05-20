package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.caseFlow.CaseNode
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant

/**
 * Base SDN entity for all [io.whozoss.agentos.sdk.caseEvent.CaseEvent] node projections.
 *
 * Carries the `@Node("CaseEvent")` primary label, the `@Id`, and all shared
 * audit/routing properties. Subclasses declare only their own subtype-specific
 * fields — they do NOT redeclare the shared properties, which would cause SDN
 * to report duplicate property definitions.
 *
 * Stored as a `(:CaseEvent)-[:BELONGS_TO]->(:Case)` edge. [caseId] is kept as a
 * plain scalar property alongside the [case] relationship field: [findActiveByCaseId]
 * filters on the scalar, while the graph edge is written on save and is available
 * for traversal. [toDomain] reads from the scalar.
 *
 * [case] is a nullable `var` so SDN can call the primary constructor before
 * injecting the @Relationship field via property injection.
 *
 * Sealed so [CaseEventNodeMapper] `when` expressions are exhaustive.
 * Node classes are pure data holders; all conversion is in [CaseEventNodeMapper].
 */
@Node("CaseEvent")
sealed class CaseEventNode(
    @Id open val id: String = "",
    open val caseId: String = "",
    open val namespaceId: String = "",
    open val timestamp: Instant = Instant.EPOCH,
    open val created: Instant = Instant.now(),
    open val createdBy: String? = null,
    open val modified: Instant = Instant.now(),
    open val modifiedBy: String? = null,
    open val removed: Boolean? = null,
) {
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var case: CaseNode? = null
}

@Node("CaseStatusEvent")
class CaseStatusEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val status: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("WarnEvent")
class WarnEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val message: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("AgentSelectedEvent")
class AgentSelectedEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val agentId: String,
    val agentName: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("AgentFinishedEvent")
class AgentFinishedEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val agentId: String,
    val agentName: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("AgentRunningEvent")
class AgentRunningEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val agentId: String,
    val agentName: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("MessageEvent")
class MessageEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val actorId: String,
    val actorDisplayName: String,
    val actorRole: String,
    /** JSON-serialised [List]<[io.whozoss.agentos.sdk.caseEvent.MessageContent]> */
    val contentJson: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("ToolRequestEvent")
class ToolRequestEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val toolRequestId: String,
    val toolName: String,
    val args: String?,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("ToolResponseEvent")
class ToolResponseEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val toolRequestId: String,
    val toolName: String,
    /** JSON-serialised [io.whozoss.agentos.sdk.caseEvent.MessageContent] */
    val outputJson: String,
    val success: Boolean = true,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("ThinkingEvent")
class ThinkingEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("QuestionEvent")
class QuestionEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val agentId: String,
    val agentName: String,
    val question: String,
    /** JSON-serialised [List]<[String]>?, null when no options */
    val options: String? = null,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("AnswerEvent")
class AnswerEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val questionId: String,
    val actorId: String,
    val actorDisplayName: String,
    val actorRole: String,
    val answer: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("IntentionGeneratedEvent")
class IntentionGeneratedEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val agentId: String,
    val intention: String,
    /** Name of the tool selected in the same LLM call that produced [intention]. Default empty for backward compat. */
    val toolName: String = "",
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("ToolSelectedEvent")
class ToolSelectedEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val agentId: String,
    val toolName: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("TextChunkEvent")
class TextChunkEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val chunk: String,
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("PendingConfirmationEvent")
class PendingConfirmationEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val toolRequestId: String,
    val toolName: String,
    val inputJson: String,
    val analysisInstructions: String = "",
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)

@Node("ConfirmationResolvedEvent")
class ConfirmationResolvedEventNode(
    id: String,
    caseId: String,
    namespaceId: String,
    timestamp: Instant,
    val pendingEventId: String,
    val confirmed: Boolean,
    /** Textual result of executeWithJson / onRejected, injected into the LLM history. */
    val resultText: String = "",
    created: Instant = Instant.now(),
    createdBy: String? = null,
    modified: Instant = Instant.now(),
    modifiedBy: String? = null,
    removed: Boolean? = null,
) : CaseEventNode(id, caseId, namespaceId, timestamp, created, createdBy, modified, modifiedBy, removed)
