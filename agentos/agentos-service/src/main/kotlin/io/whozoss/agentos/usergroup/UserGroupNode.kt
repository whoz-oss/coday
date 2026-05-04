package io.whozoss.agentos.usergroup

import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

@Node("UserGroup")
data class UserGroupNode(
    @Id
    val id: String,
    val namespaceId: String,
    val name: String,
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): UserGroup =
        UserGroup(
            metadata = EntityMetadata(
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
        fun fromDomain(userGroup: UserGroup): UserGroupNode =
            UserGroupNode(
                id = userGroup.id.toString(),
                namespaceId = userGroup.namespaceId.toString(),
                name = userGroup.name,
                created = userGroup.metadata.created,
                createdBy = userGroup.metadata.createdBy,
                modified = userGroup.metadata.modified,
                modifiedBy = userGroup.metadata.modifiedBy,
                removed = userGroup.metadata.removed.takeIf { it },
            )
    }
}
