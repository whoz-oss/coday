package io.whozoss.agentos.sdk.api.messaging

import java.util.UUID

/**
 * HTTP API contract for messaging as a user: the bots of the agents the current user can talk to, the accounts
 * they linked through them, and "Continue on Telegram" for a case.
 *
 * Implemented by `MessagingController` in agentos-service. External consumers implement this interface as a
 * Feign client, adding their own `@FeignClient` and routing annotations.
 *
 * Authorization summary (enforced server-side, not expressed here):
 * - every endpoint but [continueCaseInMessaging] needs an authenticated user, and works on that user's own
 *   accounts and on the bots of the agents they can use
 * - [continueCaseInMessaging]: WRITE on the case
 */
interface MessagingApi {
    /** GET /api/messaging/capabilities — what the instance offers, such as creating a bot with no token. */
    fun getMessagingCapabilities(): MessagingCapabilitiesDto

    /** GET /api/messaging/bots — the running bots of the agents the current user can use. */
    fun listMessagingBots(): List<MessagingBotDto>

    /**
     * POST /api/messaging/bots/{botId}/link — a single-use link that opens the bot: the account that sends it
     * back to the bot is linked to the current user. 404 when the user cannot use the bot's agent, 409 when the
     * bot is not running.
     */
    fun createMessagingLink(botId: UUID): MessagingLinkDto

    /** GET /api/messaging/identities — the messaging accounts linked to the current user. */
    fun listMessagingIdentities(): List<MessagingIdentityDto>

    /** DELETE /api/messaging/identities/{identityId} — unlinks an account of the current user; 404 otherwise. */
    fun deleteMessagingIdentity(identityId: UUID)

    /**
     * POST /api/cases/{caseId}/messaging/continue — moves the current user's direct chat with a bot to the case
     * and posts its last answer there. 409 when the bot is not running, works in another namespace, or the user
     * has linked no account through it; 502 when the platform refuses the message.
     */
    fun continueCaseInMessaging(
        caseId: UUID,
        request: MessagingContinueRequest,
    )
}
