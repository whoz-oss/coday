package io.whozoss.agentos.user

import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
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
 *
 * [promptCacheTtl] is stored as its enum name string; absent (users created before the field existed) or
 * unknown (a value removed from the enum) reads as [PromptCacheTtl.FIVE_MINUTES], so a bad value never makes
 * the user unreadable.
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
    val isAdmin: Boolean = false,
    val preferredLanguage: String? = null,
    val promptCacheTtl: String? = null,
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
            isAdmin = isAdmin,
            preferredLanguage = preferredLanguage,
            promptCacheTtl = decodePromptCacheTtl(),
        )

    private fun decodePromptCacheTtl(): PromptCacheTtl {
        val stored = promptCacheTtl ?: return PromptCacheTtl.FIVE_MINUTES
        return PromptCacheTtl.entries.firstOrNull { it.name == stored } ?: run {
            logger.warn { "User $id has an unknown promptCacheTtl '$stored', using ${PromptCacheTtl.FIVE_MINUTES}" }
            PromptCacheTtl.FIVE_MINUTES
        }
    }

    companion object : KLogging() {
        fun fromDomain(user: User): UserNode =
            UserNode(
                id = user.id.toString(),
                externalId = user.externalId,
                email = user.email,
                firstname = user.firstname,
                lastname = user.lastname,
                bio = user.bio,
                isAdmin = user.isAdmin,
                preferredLanguage = user.preferredLanguage,
                promptCacheTtl = user.promptCacheTtl.name,
                created = user.metadata.created,
                createdBy = user.metadata.createdBy,
                modified = user.metadata.modified,
                modifiedBy = user.metadata.modifiedBy,
                removed = user.metadata.removed.takeIf { it },
            )
    }
}
