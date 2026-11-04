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
 * Spring Data Neo4j projection for [MessagingBot].
 *
 * [encryptedToken] holds the token as [Neo4jMessagingBotRepository] encrypted it: the plain token is never
 * written. The two keys are stored, though derived, because a constraint can only hold on a stored property.
 */
@Node("MessagingBot")
data class MessagingBotNode(
    @Id
    val id: String,
    val platform: String,
    val role: String,
    val accountId: String,
    val username: String,
    val displayName: String,
    val encryptedToken: String,
    val agentId: String?,
    val namespaceId: String?,
    val enabled: Boolean,
    val managed: Boolean,
    val botKey: String,
    val slotKey: String,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
) {
    fun toDomain(token: String): MessagingBot =
        MessagingBot(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    version = version,
                ),
            platform = MessagingPlatform.valueOf(platform),
            role = MessagingBotRole.valueOf(role),
            accountId = accountId,
            username = username,
            displayName = displayName,
            token = token,
            agentId = agentId?.let(UUID::fromString),
            namespaceId = namespaceId?.let(UUID::fromString),
            enabled = enabled,
            managed = managed,
        )

    override fun toString(): String = "MessagingBotNode(id=$id, platform=$platform, role=$role, accountId=$accountId)"

    companion object {
        fun fromDomain(
            bot: MessagingBot,
            encryptedToken: String,
        ): MessagingBotNode =
            MessagingBotNode(
                id = bot.id.toString(),
                platform = bot.platform.name,
                role = bot.role.name,
                accountId = bot.accountId,
                username = bot.username,
                displayName = bot.displayName,
                encryptedToken = encryptedToken,
                agentId = bot.agentId?.toString(),
                namespaceId = bot.namespaceId?.toString(),
                enabled = bot.enabled,
                managed = bot.managed,
                botKey = bot.botKey,
                slotKey = bot.slotKey,
                version = bot.metadata.version,
                created = bot.metadata.created,
                createdBy = bot.metadata.createdBy,
                modified = bot.metadata.modified,
                modifiedBy = bot.metadata.modifiedBy,
            )
    }
}
