package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [AgentConfig].
 *
 * Stored as a (:AgentConfig) node with a [namespaceId] property linking it
 * to its parent namespace (represented as a property, not an SDN @Relationship).
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
 *
 * [integrationsJson] serialises [AgentConfig.integrations] as a JSON string
 * because Neo4j cannot store a map-of-lists as a native property.
 */
@Node("AgentConfig")
data class AgentConfigNode(
    @Id
    val id: String,
    val namespaceId: String,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
    val integrationsJson: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): AgentConfig =
        AgentConfig(
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
            description = description,
            instructions = instructions,
            modelName = modelName,
            integrations = integrationsJson?.let { MAPPER.readValue(it, INTEGRATIONS_TYPE) },
        )

    companion object {
        private val MAPPER = jacksonObjectMapper()
        private val INTEGRATIONS_TYPE = object : TypeReference<Map<String, List<String>?>>() {}

        fun fromDomain(config: AgentConfig): AgentConfigNode =
            AgentConfigNode(
                id = config.id.toString(),
                namespaceId = config.namespaceId.toString(),
                name = config.name,
                description = config.description,
                instructions = config.instructions,
                modelName = config.modelName,
                integrationsJson = config.integrations?.let { MAPPER.writeValueAsString(it) },
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
    }
}
