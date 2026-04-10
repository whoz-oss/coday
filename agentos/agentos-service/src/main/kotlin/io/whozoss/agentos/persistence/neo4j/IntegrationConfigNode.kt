package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationConfig
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
 * Stored as a `(:IntegrationConfig)-[:BELONGS_TO]->(:Namespace)` edge.
 *
 * [namespaceId] is kept as a plain node property alongside the [namespace]
 * relationship field. This serves two purposes:
 * - It allows [findActiveByNamespaceId] to use a simple property filter
 *   (`WHERE c.namespaceId = $namespaceId`) rather than requiring a relationship
 *   traversal in the RETURN clause for SDN to inject [namespace].
 * - It acts as a fast index-friendly filter without a graph hop.
 *
 * [namespace] is nullable with a `null` default so SDN can call the primary
 * constructor before injecting the @Relationship field via property injection.
 * [toDomain] derives [IntegrationConfig.namespaceId] from the plain [namespaceId]
 * property, which is always present regardless of whether SDN loaded the relationship.
 *
 * [parameters] is a [JsonNode] in the domain model but Neo4j has no native JSON
 * type, so it is stored as a raw JSON string ([parametersJson]) and round-tripped
 * via [ObjectMapper] in [toDomain] / [fromDomain].
 */
@Node("IntegrationConfig")
data class IntegrationConfigNode(
    @Id
    val id: String,
    val namespaceId: String,
    val name: String,
    val integrationType: String,
    val parametersJson: String? = null,
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
            namespaceId = UUID.fromString(namespaceId),
            name = name,
            integrationType = integrationType,
            parameters = parametersJson?.let { objectMapper.readTree(it) },
        )

    companion object {
        fun fromDomain(
            config: IntegrationConfig,
            objectMapper: ObjectMapper,
        ): IntegrationConfigNode =
            IntegrationConfigNode(
                id = config.id.toString(),
                namespaceId = config.namespaceId.toString(),
                name = config.name,
                integrationType = config.integrationType,
                parametersJson = config.parameters?.let { objectMapper.writeValueAsString(it) },
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
                namespace = NamespaceNode.stub(config.namespaceId),
            )
    }
}
