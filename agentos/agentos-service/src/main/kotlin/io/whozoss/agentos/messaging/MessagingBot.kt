package io.whozoss.agentos.messaging

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A bot on a messaging platform that AgentOS runs: the bot of an agent, which its users talk to, or the bot
 * manager of the instance, which creates the bots of agents (see [MessagingBotRole]).
 *
 * Bots are configuration data, taken into account at once by [MessagingBotSupervisor]: adding, turning off or
 * removing one needs no restart. The [token] is stored encrypted and never leaves the server; [toString] masks
 * it. A bot is at most one agent's ([botKey] unique), and an agent has at most one bot per platform and
 * namespace ([slotKey] unique).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessagingBot(
    override val metadata: EntityMetadata = EntityMetadata(),
    val platform: MessagingPlatform,
    val role: MessagingBotRole = MessagingBotRole.AGENT,
    /** The bot's id on the platform, e.g. the numeric Telegram bot id. Part of every identity and conversation key. */
    val accountId: String,
    /** The bot's username on the platform, without `@`. */
    val username: String,
    /** How the platform names the bot. */
    val displayName: String,
    val token: String,
    /** The agent the bot answers as. Null for a bot manager. */
    val agentId: UUID? = null,
    /** The namespace the agent answers in: the cases the bot starts belong to it. Null for a bot manager. */
    val namespaceId: UUID? = null,
    val enabled: Boolean = true,
    /** True when the instance's bot manager created the bot. */
    val managed: Boolean = false,
) : Entity {
    val botKey: String get() = botKey(platform, accountId)

    val slotKey: String
        get() =
            when (role) {
                MessagingBotRole.AGENT -> agentSlotKey(platform, requireNotNull(namespaceId), requireNotNull(agentId))
                MessagingBotRole.MANAGER -> managerSlotKey(platform)
            }

    /** What the rest of the messaging code sees of the bot: everything but its token. */
    fun ref(): MessagingBotRef =
        MessagingBotRef(
            id = id,
            platform = platform,
            role = role,
            accountId = accountId,
            username = username,
            agentId = agentId,
            namespaceId = namespaceId,
        )

    override fun toString(): String =
        "MessagingBot(id=$id, platform=$platform, role=$role, accountId=$accountId, username=$username, " +
            "agentId=$agentId, namespaceId=$namespaceId, enabled=$enabled, managed=$managed, token=****)"

    companion object {
        fun botKey(
            platform: MessagingPlatform,
            accountId: String,
        ): String = "$platform:$accountId"

        fun agentSlotKey(
            platform: MessagingPlatform,
            namespaceId: UUID,
            agentId: UUID,
        ): String = "$platform:agent:$namespaceId:$agentId"

        fun managerSlotKey(platform: MessagingPlatform): String = "$platform:manager"
    }
}

enum class MessagingBotRole {
    /** Answers as an agent: its users' messages go to the agent's cases. */
    AGENT,

    /**
     * Creates the bots of agents for their admins (Telegram's managed bots): one per platform and instance. Its
     * only conversation is the creation of a bot.
     */
    MANAGER,
}

/** A bot without its token: what adapters, the gateway and the delivery carry around. */
data class MessagingBotRef(
    val id: UUID,
    val platform: MessagingPlatform,
    val role: MessagingBotRole,
    val accountId: String,
    val username: String,
    val agentId: UUID?,
    val namespaceId: UUID?,
)
