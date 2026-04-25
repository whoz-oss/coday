package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [io.whozoss.agentos.sdk.aiProvider.AiProvider].
 *
 * Stored as `(:AiProvider)-[:BELONGS_TO]->(:Namespace)` for namespace-scoped
 * providers (Story 4.3). The [namespaceId] property keeps the scalar id for
 * the legacy `findActiveByNamespaceId` query, while the [namespace]
 * @Relationship is required by the transitive permission Cypher queries
 * (`hasAdminAccessViaNamespace` / `hasReadAccessViaNamespace`). Both sources
 * are kept in sync by [Neo4jAiProviderRepository.save].
 *
 * User-scoped providers (`userId != null`, `namespaceId == null`) do NOT get
 * the @Relationship — they are legacy and tracked for cleanup in issue #809.
 *
 * [apiKey] is stored in clear text — masking is handled at the API layer by
 * [AiProviderController], not at the persistence layer.
 *
 * [apiType] is stored as its enum name string and round-tripped via [io.whozoss.agentos.sdk.aiProvider.AiApiType.valueOf].
 */
@Node("AiProvider")
data class AiProviderNode(
    @Id
    val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val name: String,
    val description: String? = null,
    val apiType: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    val namespace: NamespaceNode? = null,
) {
    fun toDomain(): AiProvider =
        AiProvider(
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
            description = description,
            apiType = AiApiType.valueOf(apiType),
            baseUrl = baseUrl,
            apiKey = apiKey,
        )

    companion object {
        fun fromDomain(config: AiProvider): AiProviderNode =
            AiProviderNode(
                id = config.id.toString(),
                namespaceId = config.namespaceId?.toString(),
                userId = config.userId?.toString(),
                name = config.name,
                description = config.description,
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
