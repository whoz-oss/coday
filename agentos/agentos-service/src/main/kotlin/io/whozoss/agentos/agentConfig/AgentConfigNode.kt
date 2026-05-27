package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [AgentConfig].
 *
 * Stored as `(:AgentConfig)-[:BELONGS_TO]->(:Namespace)`. The [namespaceId]
 * property keeps the scalar id for the legacy `findActiveByNamespaceId` query,
 * while the [namespace] @Relationship is required by the transitive permission
 * Cypher queries (`hasAdminAccessViaNamespace` / `hasReadAccessViaNamespace`).
 * Both sources are kept in sync by [Neo4jAgentConfigRepository.save].
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
    val externalMetadataJson: String? = null,
    val advancedExecution: Boolean = false,
    val enabled: Boolean = false,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    val namespace: NamespaceNode? = null,
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
                    version = version,
                ),
            namespaceId = UUID.fromString(namespaceId),
            name = name,
            description = description,
            instructions = instructions,
            modelName = modelName,
            integrations = integrationsJson?.let { MAPPER.readValue(it, INTEGRATIONS_TYPE) },
            advancedExecution = advancedExecution,
            externalMetadata = externalMetadataJson?.let { MAPPER.readValue(it, EXTERNAL_METADATA_TYPE) },
            enabled = enabled,
        )

    companion object {
        private val MAPPER = jacksonObjectMapper()
        private val INTEGRATIONS_TYPE = object : TypeReference<Map<String, List<String>?>>() {}
        private val EXTERNAL_METADATA_TYPE = object : TypeReference<Map<String, Any?>>() {}

        fun fromDomain(config: AgentConfig): AgentConfigNode =
            AgentConfigNode(
                id = config.id.toString(),
                namespaceId = config.namespaceId.toString(),
                name = config.name,
                description = config.description,
                instructions = config.instructions,
                modelName = config.modelName,
                integrationsJson = config.integrations?.let { MAPPER.writeValueAsString(it) },
                externalMetadataJson = config.externalMetadata?.let { MAPPER.writeValueAsString(it) },
                advancedExecution = config.advancedExecution,
                version = config.metadata.version,
                enabled = config.enabled,
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
    }
}
