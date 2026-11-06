package io.whozoss.agentos.messaging

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import java.util.UUID

/**
 * Persistence for [MessagingIdentity]. The parent of an identity is its AgentOS user.
 */
interface MessagingIdentityRepository : EntityRepository<MessagingIdentity, UUID> {
    /** The active identity of a platform account, or null when the account is not linked. */
    fun findByIdentityKey(identityKey: String): MessagingIdentity?

    /** The active identity a user has linked through a bot, or null when there is none. */
    fun findByUserKey(userKey: String): MessagingIdentity?

    /** The active identities linked through the bot [accountId] of [platform]. */
    fun findByAccount(
        platform: MessagingPlatform,
        accountId: String,
    ): List<MessagingIdentity>
}
