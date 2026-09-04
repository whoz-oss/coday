package io.whozoss.agentos.usage

import io.whozoss.agentos.caseFlow.CaseNode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [UsageRecord].
 *
 * All fields are stored as scalars — no nested objects, no JSON blobs.
 * UUID values are stored as Strings (consistent with every other node in the project).
 * Enum values are stored as their [Enum.name] string.
 *
 * [caseId] is present both as a plain scalar property (for indexed filtering) and via the
 * [case] relationship field (for graph traversal). This mirrors the pattern used by
 * [io.whozoss.agentos.caseEvent.CaseEventNode].
 *
 * [case] is a nullable `var` so SDN can inject it via property injection after constructing
 * the node from the primary constructor. The BELONGS_TO edge is created separately via
 * [io.whozoss.agentos.persistence.Neo4jChildLinkService] after the node is saved — never
 * by setting this field before save, which would cause SDN to overwrite CaseNode properties.
 *
 * [removed] follows the project convention: `null` means active, `true` means soft-deleted.
 * `false` is never written — use `removed.takeIf { it }` when mapping from domain.
 */
@Node("UsageRecord")
data class UsageRecordNode(
    @Id val id: String,
    val namespaceId: String,
    val caseId: String,
    val userId: String? = null,
    val source: String,
    val outcome: String,
    val agentConfigId: String? = null,
    val agentName: String,
    val providerName: String? = null,
    val apiModelName: String? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val cost: Double? = null,
    val currency: String = "USD",
    val timestamp: Instant = Instant.now(),
    // Audit fields from EntityMetadata
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var case: CaseNode? = null

    fun toDomain(): UsageRecord =
        UsageRecord(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            namespaceId = UUID.fromString(namespaceId),
            caseId = UUID.fromString(caseId),
            userId = userId?.let { UUID.fromString(it) },
            source = UsageSource.valueOf(source),
            outcome = UsageOutcome.valueOf(outcome),
            agentConfigId = agentConfigId?.let { UUID.fromString(it) },
            agentName = agentName,
            providerName = providerName,
            apiModelName = apiModelName,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            totalTokens = totalTokens,
            cost = cost,
            currency = currency,
            timestamp = timestamp,
        )

    companion object {
        fun fromDomain(record: UsageRecord): UsageRecordNode =
            UsageRecordNode(
                id = record.id.toString(),
                namespaceId = record.namespaceId.toString(),
                caseId = record.caseId.toString(),
                userId = record.userId?.toString(),
                source = record.source.name,
                outcome = record.outcome.name,
                agentConfigId = record.agentConfigId?.toString(),
                agentName = record.agentName,
                providerName = record.providerName,
                apiModelName = record.apiModelName,
                inputTokens = record.inputTokens,
                outputTokens = record.outputTokens,
                cacheReadTokens = record.cacheReadTokens,
                cacheWriteTokens = record.cacheWriteTokens,
                totalTokens = record.totalTokens,
                cost = record.cost,
                currency = record.currency,
                timestamp = record.timestamp,
                created = record.metadata.created,
                createdBy = record.metadata.createdBy,
                modified = record.metadata.modified,
                modifiedBy = record.metadata.modifiedBy,
                // Write null for active entities, true for soft-deleted — never write false.
                removed = record.metadata.removed.takeIf { it },
            )
    }
}
