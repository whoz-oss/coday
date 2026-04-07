package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.persistence.neo4j.CaseEventNodeMapper.withRemoved
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

/**
 * Maps between [CaseEvent] domain objects and their [CaseEventNode] graph projections.
 *
 * Centralises all conversion logic so node classes remain pure data holders.
 * Also handles the soft-delete copy via [withRemoved].
 */
object CaseEventNodeMapper {
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
            is CaseStatusEventNode -> node.copy(removed = removed)
            is WarnEventNode -> node.copy(removed = removed)
            is AgentSelectedEventNode -> node.copy(removed = removed)
            is AgentFinishedEventNode -> node.copy(removed = removed)
            is AgentRunningEventNode -> node.copy(removed = removed)
            is MessageEventNode -> node.copy(removed = removed)
            is ToolRequestEventNode -> node.copy(removed = removed)
            is ToolResponseEventNode -> node.copy(removed = removed)
            is ThinkingEventNode -> node.copy(removed = removed)
            is QuestionEventNode -> node.copy(removed = removed)
            is AnswerEventNode -> node.copy(removed = removed)
            is IntentionGeneratedEventNode -> node.copy(removed = removed)
            is ToolSelectedEventNode -> node.copy(removed = removed)
            is TextChunkEventNode -> node.copy(removed = removed)
        }

    // ─── toDomain ────────────────────────────────────────────────────────────

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
            content = MessageContentSerializer.deserialize(n.contentJson),
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
            output = MessageContentSerializer.deserializeSingle(n.outputJson),
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
            options = n.options?.let { MessageContentSerializer.deserializeStringList(it) },
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

    // ─── fromDomain ──────────────────────────────────────────────────────────

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
            contentJson = MessageContentSerializer.serialize(e.content),
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
            outputJson = MessageContentSerializer.serializeSingle(e.output),
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
            options = e.options?.let { MessageContentSerializer.serializeStringList(it) },
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

    // ─── Shared helper ───────────────────────────────────────────────────────

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
