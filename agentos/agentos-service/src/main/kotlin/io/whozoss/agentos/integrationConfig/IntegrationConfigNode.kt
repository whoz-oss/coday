package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
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
 * [namespace] is a nullable `var` so SDN can call the primary constructor before
 * injecting the @Relationship field via property injection.
 *
 * [toDomain] reads [namespace]!!.id — a null here is a data-integrity error and
 * should surface as an NPE rather than be silently ignored.
 *
 * On write, [fromDomain] provides a stub [NamespaceNode] carrying only the `@Id`.
 * SDN MERGEs by `@Id` on save and never overwrites existing Namespace properties.
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
            )
    }
}
