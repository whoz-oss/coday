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
 * Stored as a (:LlmModelConfig) node with a [llmConfigId] property linking it to
 * its parent [LlmConfigNode] (represented as a property, not an SDN @Relationship,
 * consistent with the pattern used for all other parent-child relationships in this
 * codebase).
 *
 * All numeric inference parameters ([temperature], [maxTokens]) are nullable and
 * stored directly as Neo4j properties — no serialisation needed.
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
 */
@Node("LlmModelConfig")
data class LlmModelConfigNode(
    @Id
    val id: String,
    val llmConfigId: String,
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
