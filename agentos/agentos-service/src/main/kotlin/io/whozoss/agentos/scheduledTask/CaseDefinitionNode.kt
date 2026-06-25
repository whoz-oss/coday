package io.whozoss.agentos.scheduledTask

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
 * Spring Data Neo4j projection for [CaseDefinition].
 *
 * Stored as `(:CaseDefinition)-[:BELONGS_TO]->(:Namespace)`.
 *
 * All targeting fields are stored as flat scalars.
 * [namespaceId] is always set; [userGroupId] and [userId] are nullable and mutually
 * exclusive (enforced by [CaseDefinition.init]).
 *
 * ### Soft-delete convention
 *
 * [removed] is `null` for active records and `true` for soft-deleted ones.
 * Always filter with `WHERE removed IS NULL OR removed = false`.
 */
@Node("CaseDefinition")
data class CaseDefinitionNode(
    @Id
    val id: String,
    val namespaceId: String,
    val userGroupId: String? = null,
    val userId: String? = null,
    val name: String,
    val description: String? = null,
    val agentId: String,
    val prompt: String,
    /** Standard 5-field cron expression, e.g. `"0 9 * * *"` or `"0 9 * * MON"`. */
    val cronExpression: String,
    val enabled: Boolean = true,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    val namespace: NamespaceNode? = null,
) {
    fun toDomain(): CaseDefinition =
        CaseDefinition(
            metadata = EntityMetadata(
                id = UUID.fromString(id),
                created = created,
                createdBy = createdBy,
                modified = modified,
                modifiedBy = modifiedBy,
                removed = removed ?: false,
                version = version,
            ),
            namespaceId = UUID.fromString(namespaceId),
            userGroupId = userGroupId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            name = name,
            description = description,
            agentId = UUID.fromString(agentId),
            prompt = prompt,
            cronExpression = cronExpression,
            enabled = enabled,
        )

    companion object {
        fun fromDomain(def: CaseDefinition): CaseDefinitionNode =
            CaseDefinitionNode(
                id = def.id.toString(),
                namespaceId = def.namespaceId.toString(),
                userGroupId = def.userGroupId?.toString(),
                userId = def.userId?.toString(),
                name = def.name,
                description = def.description,
                agentId = def.agentId.toString(),
                prompt = def.prompt,
                cronExpression = def.cronExpression,
                enabled = def.enabled,
                version = def.metadata.version,
                created = def.metadata.created,
                createdBy = def.metadata.createdBy,
                modified = def.metadata.modified,
                modifiedBy = def.metadata.modifiedBy,
                removed = def.metadata.removed.takeIf { it },
            )
    }
}
