package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [User].
 *
 * Stored as a (:User) node. No parent relationship — users are root-level.
 *
 * [externalId] is promoted to a first-class node property so that
 * [UserNodeNeo4jRepository.findActiveByExternalId] can use an indexed lookup
 * rather than a full scan.
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
 */
@Node("User")
data class UserNode(
    @Id
    val id: String,
    val externalId: String,
    val email: String,
    val firstname: String? = null,
    val lastname: String? = null,
    val bio: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): User =
        User(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            externalId = externalId,
            email = email,
            firstname = firstname,
            lastname = lastname,
            bio = bio,
        )

    companion object {
        fun fromDomain(user: User): UserNode =
            UserNode(
                id = user.id.toString(),
                externalId = user.externalId,
                email = user.email,
                firstname = user.firstname,
                lastname = user.lastname,
                bio = user.bio,
                created = user.metadata.created,
                createdBy = user.metadata.createdBy,
                modified = user.metadata.modified,
                modifiedBy = user.metadata.modifiedBy,
                removed = user.metadata.removed.takeIf { it },
            )
    }
}
