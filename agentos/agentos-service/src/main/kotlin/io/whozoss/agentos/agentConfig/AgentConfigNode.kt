package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
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
                ),
            namespaceId = UUID.fromString(namespaceId),
            name = name,
            description = description,
            instructions = instructions,
            modelName = modelName,
        )

    companion object {
        fun fromDomain(config: AgentConfig): AgentConfigNode =
            AgentConfigNode(
                id = config.id.toString(),
                namespaceId = config.namespaceId.toString(),
                name = config.name,
                description = config.description,
                instructions = config.instructions,
                modelName = config.modelName,
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
    }
}
