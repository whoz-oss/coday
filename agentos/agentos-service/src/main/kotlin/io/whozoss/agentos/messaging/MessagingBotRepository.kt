package io.whozoss.agentos.messaging

import java.util.UUID

/**
 * Persistence for [MessagingBot]. A bot is removed for good: its token must not linger.
 */
interface MessagingBotRepository {
    fun save(bot: MessagingBot): MessagingBot

    fun findById(id: UUID): MessagingBot?

    /** Every bot that can be read; one that cannot is left out, so that the others still run and list. */
    fun findAll(): List<MessagingBot>

    fun findByRole(role: MessagingBotRole): List<MessagingBot>

    /** The bot with this platform id, whatever it serves. */
    fun findByBotKey(botKey: String): MessagingBot?

    /** The bot holding an agent's place on a platform, or the bot manager's. */
    fun findBySlotKey(slotKey: String): MessagingBot?

    /** The bots of the agents of [namespaceId]. */
    fun findAgentBotsByNamespace(namespaceId: UUID): List<MessagingBot>

    /** The bots of [agentId], in every namespace. */
    fun findByAgentId(agentId: UUID): List<MessagingBot>

    /** Deletes the bot and its token. False when there was none. */
    fun delete(id: UUID): Boolean
}
