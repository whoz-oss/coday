package io.whozoss.agentos.aiModel

import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [AiModel].
 *
 * Stored as a (:AiModel) node. Parent relationships are represented as
 * plain string properties (not SDN @Relationship), consistent with the pattern
 * used throughout this codebase.
 *
 * [namespaceId] and [userId] are denormalised from the parent [io.whozoss.agentos.aiProvider.AiProviderNode] at
 * creation time so that namespace-scoped queries can be served with a single
 * WHERE clause without graph traversal.
 */
@Node("AiModel")
data class AiModelNode(
    @Id
    val id: String,
    val aiProviderId: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val apiName: String,
    val description: String? = null,
    val alias: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): AiModel =
        AiModel(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            aiProviderId = UUID.fromString(aiProviderId),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            apiName = apiName,
            description = description,
            alias = alias,
            priority = priority,
            temperature = temperature,
            maxTokens = maxTokens,
        )

    companion object {
        fun fromDomain(model: AiModel): AiModelNode =
            AiModelNode(
                id = model.id.toString(),
                aiProviderId = model.aiProviderId.toString(),
                namespaceId = model.namespaceId?.toString(),
                userId = model.userId?.toString(),
                apiName = model.apiName,
                description = model.description,
                alias = model.alias,
                priority = model.priority,
                temperature = model.temperature,
                maxTokens = model.maxTokens,
                created = model.metadata.created,
                createdBy = model.metadata.createdBy,
                modified = model.metadata.modified,
                modifiedBy = model.metadata.modifiedBy,
                removed = model.metadata.removed.takeIf { it },
            )
    }
}
