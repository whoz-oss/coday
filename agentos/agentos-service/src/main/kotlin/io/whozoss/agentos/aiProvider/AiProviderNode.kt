package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.TripleKeyEncoding
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
 * providers. The [namespaceId] property keeps the scalar id for
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
    /**
     * Denormalised discriminator for the unique business triple `(namespaceId, userId,
     * name)`. Backed by a UNIQUE CONSTRAINT (cf. `AiProviderSchemaInitializer`). Same
     * pattern as [io.whozoss.agentos.integrationConfig.IntegrationConfigNode.tripleKey] —
     * see [TripleKeyEncoding] and the RFC §D11 for rationale.
     *
     * Soft-deleted rows carry a per-id `tombstone:<uuid>` value so the unique slot is
     * freed for re-creation immediately after a delete.
     *
     * Backfilled at startup for pre-existing rows by the schema initializer.
     */
    val tripleKey: String,
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
        fun computeTripleKey(
            namespaceId: UUID?,
            userId: UUID?,
            name: String,
        ): String = TripleKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = TripleKeyEncoding.tombstoneKey(id)

        fun fromDomain(config: AiProvider): AiProviderNode {
            val idString = config.id.toString()
            val tripleKey =
                when {
                    config.metadata.removed -> tombstoneTripleKey(idString)
                    else -> computeTripleKey(config.namespaceId, config.userId, config.name)
                }
            return AiProviderNode(
                id = idString,
                namespaceId = config.namespaceId?.toString(),
                userId = config.userId?.toString(),
                name = config.name,
                tripleKey = tripleKey,
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
}
