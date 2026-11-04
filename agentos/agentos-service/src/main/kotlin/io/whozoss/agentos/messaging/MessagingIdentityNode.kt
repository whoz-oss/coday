package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [MessagingIdentity].
 *
 * An active row also carries the `ActiveMessagingIdentity` label, set and removed by
 * [Neo4jMessagingIdentityRepository]. The uniqueness of [identityKey] and [userKey] is declared on that
 * label, so soft deletion frees both slots by removing it.
 *
 * The two keys are stored, though derived, because a constraint can only hold on a stored property.
 */
@Node("MessagingIdentity")
data class MessagingIdentityNode(
    @Id
    val id: String,
    val platform: String,
    val accountId: String,
    val externalUserId: String,
    val directChatId: String,
    val displayName: String,
    val userId: String,
    val identityKey: String,
    val userKey: String,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): MessagingIdentity =
        MessagingIdentity(
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
            platform = MessagingPlatform.valueOf(platform),
            accountId = accountId,
            externalUserId = externalUserId,
            directChatId = directChatId,
            displayName = displayName,
            userId = UUID.fromString(userId),
        )

    companion object {
        fun fromDomain(identity: MessagingIdentity): MessagingIdentityNode =
            MessagingIdentityNode(
                id = identity.id.toString(),
                platform = identity.platform.name,
                accountId = identity.accountId,
                externalUserId = identity.externalUserId,
                directChatId = identity.directChatId,
                displayName = identity.displayName,
                userId = identity.userId.toString(),
                identityKey = identity.identityKey,
                userKey = identity.userKey,
                version = identity.metadata.version,
                created = identity.metadata.created,
                createdBy = identity.metadata.createdBy,
                modified = identity.metadata.modified,
                modifiedBy = identity.metadata.modifiedBy,
                removed = identity.metadata.removed.takeIf { it },
            )
    }
}
