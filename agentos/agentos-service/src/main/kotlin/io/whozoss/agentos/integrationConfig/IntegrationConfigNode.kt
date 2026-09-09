package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [IntegrationConfig].
 *
 * Triple-mode (since story 6.1): both [namespaceId] and [userId] are nullable scalar properties.
 * For namespace-scoped configs the BELONGS_TO edge to the parent Namespace is materialised by
 * [Neo4jIntegrationConfigRepository.save] via [io.whozoss.agentos.persistence.Neo4jChildLinkService].
 * User-only configs (`namespaceId == null`) do NOT yet get a BELONGS_TO edge to the user node —
 * that wiring lands in story 6.2 alongside the user-scoped CRUD.
 *
 * [namespace] is a nullable `var` so SDN can call the primary constructor before
 * injecting the @Relationship field via property injection.
 *
 * On write, [fromDomain] provides a stub [NamespaceNode] carrying only the `@Id`.
 * SDN MERGEs by `@Id` on save and never overwrites existing Namespace properties.
 *
 * [parameters] is a [com.fasterxml.jackson.databind.JsonNode] in the domain model but Neo4j has
 * no native JSON type, so it is stored as a raw JSON string ([parametersJson]) and round-tripped
 * via [ObjectMapper] in [toDomain] / [fromDomain].
 *
 * [tripleKey] is a denormalised, deterministic discriminator computed from
 * `(namespaceId, userId, name)` (story 6.2.5). It backs both the unique constraint and the
 * single-property index seek used by [IntegrationConfigNodeNeo4jRepository.findActiveByTripleKey].
 * Sentinel `_` (underscore — invalid in UUIDs) substitutes a NULL id so two distinct triples
 * never collide. The key stays in sync with its sources because all writes flow through
 * [fromDomain]; reads (and `copy(removed = true)` soft deletes) preserve the stored value.
 */
@Node("IntegrationConfig")
data class IntegrationConfigNode(
    @Id
    val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val name: String,
    val tripleKey: String,
    val integrationType: String,
    val description: String? = null,
    val parametersJson: String? = null,
    val authSettingName: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,
) {
    fun toDomain(objectMapper: ObjectMapper): IntegrationConfig =
        IntegrationConfig(
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
            integrationType = integrationType,
            description = description,
            parameters = parametersJson?.let { objectMapper.readTree(it) },
            authSettingName = authSettingName,
        )

    companion object {
        fun computeTripleKey(
            namespaceId: UUID?,
            userId: UUID?,
            name: String,
        ): String = OverlayKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(
            config: IntegrationConfig,
            objectMapper: ObjectMapper,
        ): IntegrationConfigNode {
            val idString = config.id.toString()
            val tripleKey =
                when {
                    config.metadata.removed -> tombstoneTripleKey(idString)
                    else -> computeTripleKey(config.namespaceId, config.userId, config.name)
                }
            return IntegrationConfigNode(
                id = idString,
                namespaceId = config.namespaceId?.toString(),
                userId = config.userId?.toString(),
                name = config.name,
                tripleKey = tripleKey,
                integrationType = config.integrationType,
                description = config.description,
                parametersJson = config.parameters?.let { objectMapper.writeValueAsString(it) },
                authSettingName = config.authSettingName,
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
        }
    }
}
