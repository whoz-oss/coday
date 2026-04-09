package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [IntegrationConfig].
 *
 * Stored as a (:IntegrationConfig) node with a [namespaceId] property linking it
 * to its parent namespace (represented as a property, not an SDN @Relationship).
 *
 * [parameters] is a [JsonNode] in the domain model but Neo4j has no native JSON
 * type, so it is stored as a raw JSON string ([parametersJson]) and round-tripped
 * via [ObjectMapper] in [toDomain] / [fromDomain].
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
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
