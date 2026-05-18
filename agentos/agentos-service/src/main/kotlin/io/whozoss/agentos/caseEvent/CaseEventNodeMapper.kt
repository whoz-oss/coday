package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.IntentionGeneratedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.TextChunkEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.ToolSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

// Note: fromDomain does NOT set the `case` @Relationship field. The BELONGS_TO
// edge is created separately via Neo4jChildLinkService.link(...) in
// Neo4jCaseEventRepository after the node is saved. This avoids SDN writing
// stub CaseNode properties (empty status/title) onto the existing Case node.

/**
 * Maps between [CaseEvent] domain objects and their [CaseEventNode] graph projections.
 *
 * Centralises all conversion logic so node classes remain pure data holders.
 * Also handles the soft-delete copy via [withRemoved].
 *
 * Receives a [MessageContentSerializer] backed by the application-wide [ObjectMapper]
 * so that [MessageContent] polymorphic (de)serialisation works correctly.
 */
class CaseEventNodeMapper(
    private val serializer: MessageContentSerializer,
) {
    fun toDomain(node: CaseEventNode): CaseEvent =
        when (node) {
            is CaseStatusEventNode -> toDomain(node)
            is WarnEventNode -> toDomain(node)
            is AgentSelectedEventNode -> toDomain(node)
            is AgentFinishedEventNode -> toDomain(node)
            is AgentRunningEventNode -> toDomain(node)
            is MessageEventNode -> toDomain(node)
            is ToolRequestEventNode -> toDomain(node)
            is ToolResponseEventNode -> toDomain(node)
            is ThinkingEventNode -> toDomain(node)
            is QuestionEventNode -> toDomain(node)
            is AnswerEventNode -> toDomain(node)
            is IntentionGeneratedEventNode -> toDomain(node)
            is ToolSelectedEventNode -> toDomain(node)
            is TextChunkEventNode -> toDomain(node)
        }

    fun fromDomain(event: CaseEvent): CaseEventNode =
        when (event) {
            is CaseStatusEvent -> fromDomain(event)
            is WarnEvent -> fromDomain(event)
            is AgentSelectedEvent -> fromDomain(event)
            is AgentFinishedEvent -> fromDomain(event)
            is AgentRunningEvent -> fromDomain(event)
            is MessageEvent -> fromDomain(event)
            is ToolRequestEvent -> fromDomain(event)
            is ToolResponseEvent -> fromDomain(event)
            is ThinkingEvent -> fromDomain(event)
            is QuestionEvent -> fromDomain(event)
            is AnswerEvent -> fromDomain(event)
            is IntentionGeneratedEvent -> fromDomain(event)
            is ToolSelectedEvent -> fromDomain(event)
            is TextChunkEvent -> fromDomain(event)
        }

    fun withRemoved(
        node: CaseEventNode,
        removed: Boolean?,
    ): CaseEventNode =
        when (node) {
            is CaseStatusEventNode ->
                CaseStatusEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.status,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is WarnEventNode ->
                WarnEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.message,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is AgentSelectedEventNode ->
                AgentSelectedEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.agentId,
                    node.agentName,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is AgentFinishedEventNode ->
                AgentFinishedEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.agentId,
                    node.agentName,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is AgentRunningEventNode ->
                AgentRunningEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.agentId,
                    node.agentName,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is MessageEventNode ->
                MessageEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.actorId,
                    node.actorDisplayName,
                    node.actorRole,
                    node.contentJson,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is ToolRequestEventNode ->
                ToolRequestEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.toolRequestId,
                    node.toolName,
                    node.args,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is ToolResponseEventNode ->
                ToolResponseEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.toolRequestId,
                    node.toolName,
                    node.outputJson,
                    node.success,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is ThinkingEventNode ->
                ThinkingEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is QuestionEventNode ->
                QuestionEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.agentId,
                    node.agentName,
                    node.question,
                    node.options,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is AnswerEventNode ->
                AnswerEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.questionId,
                    node.actorId,
                    node.actorDisplayName,
                    node.actorRole,
                    node.answer,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is IntentionGeneratedEventNode ->
                IntentionGeneratedEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.agentId,
                    node.intention,
                    node.toolName,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is ToolSelectedEventNode ->
                ToolSelectedEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.agentId,
                    node.toolName,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
            is TextChunkEventNode ->
                TextChunkEventNode(
                    node.id,
                    node.caseId,
                    node.namespaceId,
                    node.timestamp,
                    node.chunk,
                    node.created,
                    node.createdBy,
                    node.modified,
                    node.modifiedBy,
                    removed,
                )
        }

    // ─── toDomain ──────────────────────────────────────────────────────────────────────────

    private fun toDomain(n: CaseStatusEventNode) =
        CaseStatusEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            status = CaseStatus.valueOf(n.status),
        )

    private fun toDomain(n: WarnEventNode) =
        WarnEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            message = n.message,
        )

    private fun toDomain(n: AgentSelectedEventNode) =
        AgentSelectedEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            agentId = UUID.fromString(n.agentId),
            agentName = n.agentName,
        )

    private fun toDomain(n: AgentFinishedEventNode) =
        AgentFinishedEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            agentId = UUID.fromString(n.agentId),
            agentName = n.agentName,
        )

    private fun toDomain(n: AgentRunningEventNode) =
        AgentRunningEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            agentId = UUID.fromString(n.agentId),
            agentName = n.agentName,
        )

    private fun toDomain(n: MessageEventNode) =
        MessageEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            actor = Actor(id = n.actorId, displayName = n.actorDisplayName, role = ActorRole.valueOf(n.actorRole)),
            content = serializer.deserialize(n.contentJson),
        )

    private fun toDomain(n: ToolRequestEventNode) =
        ToolRequestEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            toolRequestId = n.toolRequestId,
            toolName = n.toolName,
            args = n.args,
        )

    private fun toDomain(n: ToolResponseEventNode) =
        ToolResponseEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            toolRequestId = n.toolRequestId,
            toolName = n.toolName,
            output = serializer.deserializeSingle(n.outputJson),
            success = n.success,
        )

    private fun toDomain(n: ThinkingEventNode) =
        ThinkingEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
        )

    private fun toDomain(n: QuestionEventNode) =
        QuestionEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            agentId = UUID.fromString(n.agentId),
            agentName = n.agentName,
            question = n.question,
            options = n.options?.let { serializer.deserializeStringList(it) },
        )

    private fun toDomain(n: AnswerEventNode) =
        AnswerEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            questionId = UUID.fromString(n.questionId),
            actor = Actor(id = n.actorId, displayName = n.actorDisplayName, role = ActorRole.valueOf(n.actorRole)),
            answer = n.answer,
        )

    private fun toDomain(n: IntentionGeneratedEventNode) =
        IntentionGeneratedEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            agentId = UUID.fromString(n.agentId),
            intention = n.intention,
            toolName = n.toolName,
        )

    private fun toDomain(n: ToolSelectedEventNode) =
        ToolSelectedEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            agentId = UUID.fromString(n.agentId),
            toolName = n.toolName,
        )

    private fun toDomain(n: TextChunkEventNode) =
        TextChunkEvent(
            metadata = metadata(n),
            namespaceId = UUID.fromString(n.namespaceId),
            caseId = UUID.fromString(n.caseId),
            timestamp = n.timestamp,
            chunk = n.chunk,
        )

    // ─── fromDomain ───────────────────────────────────────────────────────────────────────

    private fun fromDomain(e: CaseStatusEvent) =
        CaseStatusEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            status = e.status.name,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: WarnEvent) =
        WarnEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            message = e.message,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: AgentSelectedEvent) =
        AgentSelectedEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            agentId = e.agentId.toString(),
            agentName = e.agentName,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: AgentFinishedEvent) =
        AgentFinishedEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            agentId = e.agentId.toString(),
            agentName = e.agentName,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: AgentRunningEvent) =
        AgentRunningEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            agentId = e.agentId.toString(),
            agentName = e.agentName,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: MessageEvent) =
        MessageEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            actorId = e.actor.id,
            actorDisplayName = e.actor.displayName,
            actorRole = e.actor.role.name,
            contentJson = serializer.serialize(e.content),
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: ToolRequestEvent) =
        ToolRequestEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            toolRequestId = e.toolRequestId,
            toolName = e.toolName,
            args = e.args,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: ToolResponseEvent) =
        ToolResponseEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            toolRequestId = e.toolRequestId,
            toolName = e.toolName,
            outputJson = serializer.serializeSingle(e.output),
            success = e.success,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: ThinkingEvent) =
        ThinkingEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: QuestionEvent) =
        QuestionEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            agentId = e.agentId.toString(),
            agentName = e.agentName,
            question = e.question,
            options = e.options?.let { serializer.serializeStringList(it) },
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: AnswerEvent) =
        AnswerEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            questionId = e.questionId.toString(),
            actorId = e.actor.id,
            actorDisplayName = e.actor.displayName,
            actorRole = e.actor.role.name,
            answer = e.answer,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: IntentionGeneratedEvent) =
        IntentionGeneratedEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            agentId = e.agentId.toString(),
            intention = e.intention,
            toolName = e.toolName,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: ToolSelectedEvent) =
        ToolSelectedEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            agentId = e.agentId.toString(),
            toolName = e.toolName,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    private fun fromDomain(e: TextChunkEvent) =
        TextChunkEventNode(
            id = e.id.toString(),
            caseId = e.caseId.toString(),
            namespaceId = e.namespaceId.toString(),
            timestamp = e.timestamp,
            chunk = e.chunk,
            created = e.metadata.created,
            createdBy = e.metadata.createdBy,
            modified = e.metadata.modified,
            modifiedBy = e.metadata.modifiedBy,
            removed = e.metadata.removed.takeIf { it },
        )

    // ─── Shared helper ─────────────────────────────────────────────────────────────────────

    private fun metadata(n: CaseEventNode) =
        EntityMetadata(
            id = UUID.fromString(n.id),
            created = n.created,
            createdBy = n.createdBy,
            modified = n.modified,
            modifiedBy = n.modifiedBy,
            removed = n.removed ?: false,
        )
}
