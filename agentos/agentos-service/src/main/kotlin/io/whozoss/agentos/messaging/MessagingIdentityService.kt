package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import mu.KLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Links messaging accounts to AgentOS users, through a bot, and finds them back.
 *
 * Linking takes a token from [MessagingLinkTokens]: the user asked for it while signed in to AgentOS, and the
 * account that sends it back to the bot is the one being linked. The last link wins: an account linked again
 * leaves its previous user, and a user linking a second account through the same bot drops the first one.
 * Linking through one bot says nothing about another: the same person talking to two agents' bots is two
 * identities.
 */
@Service
class MessagingIdentityService(
    private val repository: MessagingIdentityRepository,
    private val linkTokens: MessagingLinkTokens,
) {
    /** The AgentOS user an account is linked to through a bot, or null when it is not linked. */
    fun findByAccount(
        platform: MessagingPlatform,
        accountId: String,
        externalUserId: String,
    ): MessagingIdentity? = repository.findByIdentityKey(MessagingIdentity.identityKey(platform, accountId, externalUserId))

    /** The accounts [userId] has linked. */
    fun findByUser(userId: UUID): List<MessagingIdentity> = repository.findByParent(userId)

    /** The account [userId] has linked through the bot [accountId] of [platform], or null when there is none. */
    fun findByUser(
        userId: UUID,
        platform: MessagingPlatform,
        accountId: String,
    ): MessagingIdentity? = repository.findByUserKey(MessagingIdentity.userKey(platform, accountId, userId))

    /** Starts the link flow for [userId]: the token to send to [bot]. */
    fun issueLinkToken(
        userId: UUID,
        bot: MessagingBotRef,
    ): IssuedLinkToken = linkTokens.issue(userId, bot.id)

    /**
     * Links the account that sent [token] to [bot] to the user who asked for it. Null when the token is unknown,
     * expired, already used, or was issued for another bot.
     *
     * Transactional itself: the link it delegates to is called on this instance, past the proxy, and replacing
     * the previous links must not happen without the new one.
     */
    @Transactional
    fun link(
        token: String,
        bot: MessagingBotRef,
        account: LinkedAccount,
    ): MessagingIdentity? = linkTokens.consume(token, bot.id)?.let { userId -> link(userId, account) }

    /**
     * Links [account] to [userId], replacing the account's previous link and the user's previous account on the
     * same bot. The caller has proven both ends, as the bot manager does for the creator of a bot.
     */
    @Transactional
    fun link(
        userId: UUID,
        account: LinkedAccount,
    ): MessagingIdentity {
        val identity =
            MessagingIdentity(
                platform = account.platform,
                accountId = account.accountId,
                externalUserId = account.externalUserId,
                directChatId = account.directChatId,
                displayName = account.displayName,
                userId = userId,
            )
        listOfNotNull(repository.findByIdentityKey(identity.identityKey), repository.findByUserKey(identity.userKey))
            .distinctBy { it.id }
            .forEach { repository.delete(it.id) }
        return repository.save(identity).also {
            logger.info { "[Messaging] ${account.platform} account ${account.externalUserId} linked to user $userId through bot ${account.accountId}" }
        }
    }

    /** Removes the link [identityId] of [userId]. False when the user has no such link. */
    fun unlink(
        userId: UUID,
        identityId: UUID,
    ): Boolean =
        repository
            .findById(identityId)
            ?.takeIf { it.userId == userId }
            ?.let { repository.delete(it.id) } ?: false

    /** Removes every link made through the bot [accountId] of [platform], when the bot is removed. */
    @Transactional
    fun unlinkAll(
        platform: MessagingPlatform,
        accountId: String,
    ): Int = repository.findByAccount(platform, accountId).count { repository.delete(it.id) }

    companion object : KLogging()
}

/** The messaging account that sent a link token, as the bot sees it. */
data class LinkedAccount(
    val platform: MessagingPlatform,
    val accountId: String,
    val externalUserId: String,
    val directChatId: String,
    val displayName: String,
)
