package io.whozoss.agentos.userGroup

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.time.Instant.now
import java.util.UUID

@Node("UserGroup")
data class UserGroupNode(
    @Id
    val id: String,
    val namespaceId: String,
    val name: String,
    val created: Instant = now(),
    val createdBy: String? = null,
    val modified: Instant = now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    val namespace: NamespaceNode? = null,
) {
    fun toDomain(): UserGroup =
        UserGroup(
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
        )

    companion object {
        fun fromDomain(group: UserGroup): UserGroupNode =
            UserGroupNode(
                id = group.id.toString(),
                namespaceId = group.namespaceId.toString(),
                name = group.name,
                created = group.metadata.created,
                createdBy = group.metadata.createdBy,
                modified = group.metadata.modified,
                modifiedBy = group.metadata.modifiedBy,
                removed = group.metadata.removed.takeIf { it },
            )
    }
}
