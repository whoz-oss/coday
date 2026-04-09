package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.llmModelConfig.LlmModelConfig
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [LlmModelConfig].
 *
 * Stored as a (:LlmModelConfig) node. Parent relationships are represented as
 * plain string properties (not SDN @Relationship), consistent with the pattern
 * used throughout this codebase.
 *
 * [namespaceId] and [userId] are denormalised from the parent [LlmConfigNode] at
 * creation time so that namespace-scoped queries can be served with a single
 * WHERE clause without graph traversal.
 */
@Node("LlmModelConfig")
data class LlmModelConfigNode(
    @Id
    val id: String,
    val llmConfigId: String,
    val namespaceId: String,
    val userId: String? = null,
    val apiName: String,
    val alias: String? = null,
    val displayName: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): LlmModelConfig =
        LlmModelConfig(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            llmConfigId = UUID.fromString(llmConfigId),
            namespaceId = UUID.fromString(namespaceId),
            userId = userId?.let { UUID.fromString(it) },
            apiName = apiName,
            alias = alias,
            displayName = displayName,
            temperature = temperature,
            maxTokens = maxTokens,
        )

    companion object {
        fun fromDomain(model: LlmModelConfig): LlmModelConfigNode =
            LlmModelConfigNode(
                id = model.id.toString(),
                llmConfigId = model.llmConfigId.toString(),
                namespaceId = model.namespaceId.toString(),
                userId = model.userId?.toString(),
                apiName = model.apiName,
                alias = model.alias,
                displayName = model.displayName,
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
