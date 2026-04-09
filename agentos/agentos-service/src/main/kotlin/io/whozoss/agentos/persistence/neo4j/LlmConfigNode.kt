package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.llmConfig.LlmConfig
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [LlmConfig].
 *
 * Stored as a (:LlmConfig) node with a [namespaceId] property linking it to its
 * parent namespace (represented as a property, not an SDN @Relationship).
 *
 * [apiKey] is stored in clear text — masking is handled at the API layer by
 * [io.whozoss.agentos.llmConfig.LlmConfigController], not at the persistence layer.
 *
 * [apiType] is stored as its enum name string and round-tripped via [AiApiType.valueOf].
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
 */
@Node("LlmConfig")
data class LlmConfigNode(
    @Id
    val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val name: String,
    val apiType: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): LlmConfig =
        LlmConfig(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            name = name,
            apiType = AiApiType.valueOf(apiType),
            baseUrl = baseUrl,
            apiKey = apiKey,
        )

    companion object {
        fun fromDomain(config: LlmConfig): LlmConfigNode =
            LlmConfigNode(
                id = config.id.toString(),
                namespaceId = config.namespaceId?.toString(),
                userId = config.userId?.toString(),
                name = config.name,
                apiType = config.apiType.name,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
    }
}
