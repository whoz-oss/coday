package io.whozoss.agentos.messaging

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A messaging account linked to an AgentOS user: the bot answers its messages as [userId].
 *
 * Created only by the link flow of [MessagingIdentityService], which proves both ends: the AgentOS user
 * generated the link while signed in, and the platform account opened it. A platform account is linked to at
 * most one user, and a user has at most one account per bot ([identityKey] and [userKey], unique among
 * active identities).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessagingIdentity(
    override val metadata: EntityMetadata = EntityMetadata(),
    val platform: MessagingPlatform,
    /** The bot the account was linked through. The same person talking to two bots is two identities. */
    val accountId: String,
    /** The user's id on the platform, e.g. the numeric Telegram user id. */
    val externalUserId: String,
    /** The chat where the bot talks to the user alone. For Telegram, the private chat with the bot. */
    val directChatId: String,
    /** How the platform names the account, shown in the profile. */
    val displayName: String,
    val userId: UUID,
) : Entity {
    val identityKey: String get() = identityKey(platform, accountId, externalUserId)

    val userKey: String get() = userKey(platform, accountId, userId)

    companion object {
        fun identityKey(
            platform: MessagingPlatform,
            accountId: String,
            externalUserId: String,
        ): String = "$platform:$accountId:$externalUserId"

        fun userKey(
            platform: MessagingPlatform,
            accountId: String,
            userId: UUID,
        ): String = "$platform:$accountId:$userId"
    }
}
