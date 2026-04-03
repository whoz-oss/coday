package io.whozoss.agentos.user

import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * API resource (DTO) for [User].
 *
 * Separates the HTTP contract from the domain entity so that the API surface
 * can evolve independently from the internal model. All controller endpoints
 * accept and return [UserResource] rather than [User] directly.
 *
 * Conversions are handled by [UserResource.toUser] and [User.toResource].
 */
data class UserResource(
    val id: UUID?,
    val externalId: String,
    val email: String,
    val firstname: String? = null,
    val lastname: String? = null,
    val bio: String? = null,
) {
    /**
     * Convert this resource to a domain [User].
     *
     * When [id] is null (creation request), a new [EntityMetadata] with a
     * generated UUID is used. When [id] is present (update request), the
     * existing metadata id is preserved so the service can locate the record.
     */
    fun toUser(): User =
        User(
            metadata = if (id != null) EntityMetadata(id = id) else EntityMetadata(),
            externalId = externalId,
            email = email,
            firstname = firstname,
            lastname = lastname,
            bio = bio,
        )
}

/**
 * Convert a domain [User] to its [UserResource] representation.
 */
fun User.toResource(): UserResource =
    UserResource(
        id = metadata.id,
        externalId = externalId,
        email = email,
        firstname = firstname,
        lastname = lastname,
        bio = bio,
    )
