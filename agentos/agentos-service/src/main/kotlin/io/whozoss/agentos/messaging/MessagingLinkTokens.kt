package io.whozoss.agentos.messaging

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The tokens of the link flow: a signed-in user asks for one to link an account through a bot, opens it in the
 * messaging application, and the bot that receives it links the account that opened it to that user. A token
 * lives [MessagingProperties.linkTokenTtlMinutes] and works only with the bot it was issued for.
 */
@Component
class MessagingLinkTokens(
    properties: MessagingProperties,
    clock: Clock,
) {
    private val tokens = SingleUseTokens<LinkRequest>(Duration.ofMinutes(properties.linkTokenTtlMinutes), clock)

    /** Issues a token for [userId] to link an account through [botId], replacing any the user still had for it. */
    fun issue(
        userId: UUID,
        botId: UUID,
    ): IssuedLinkToken = tokens.issue(LinkRequest(userId, botId)) { it.userId == userId && it.botId == botId }

    /**
     * Uses [token] up and returns the user who asked for it, or null when it is unknown, expired, or was issued
     * for another bot. A token works once, even when it is refused.
     */
    fun consume(
        token: String,
        botId: UUID,
    ): UUID? = tokens.consume(token)?.takeIf { it.botId == botId }?.userId

    private data class LinkRequest(
        val userId: UUID,
        val botId: UUID,
    )
}
