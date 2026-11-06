package io.whozoss.agentos.messaging

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.sdk.api.messaging.MessagingBotDto
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingBotManagerDto
import org.springframework.stereotype.Component
import java.util.UUID

/** The API's views of bots: what a bot shows of itself to a user, never its token. */
@Component
class MessagingBotViews(
    private val agentConfigService: AgentConfigService,
    private val registry: MessagingBotRegistry,
    private val identities: MessagingIdentityService,
) {
    /** [bot] as [userId] sees it: whether they have linked an account through it. [agentName] when already read. */
    fun toDto(
        bot: MessagingBot,
        userId: UUID,
        agentName: String? = null,
    ): MessagingBotDto =
        MessagingBotDto(
            id = bot.id,
            platform = bot.platform,
            agentId = requireNotNull(bot.agentId) { "The bot of an agent has an agent" },
            agentName = agentName ?: bot.agentId.let { agentConfigService.findById(it)?.name } ?: "",
            namespaceId = requireNotNull(bot.namespaceId) { "The bot of an agent has a namespace" },
            username = bot.username,
            displayName = bot.displayName,
            enabled = bot.enabled,
            managed = bot.managed,
            status = status(bot),
            url = bot.url,
            linked = identities.findByUser(userId, bot.platform, bot.accountId) != null,
            createdAt = bot.metadata.created,
        )

    fun toManagerDto(manager: MessagingBot): MessagingBotManagerDto =
        MessagingBotManagerDto(
            platform = manager.platform,
            username = manager.username,
            status = status(manager),
            createdAt = manager.metadata.created,
        )

    /** As the supervisor last saw it; a bot it does not know yet is starting, or off. */
    private fun status(bot: MessagingBot): MessagingBotStatus =
        registry.status(bot.id) ?: if (bot.enabled) MessagingBotStatus.STARTING else MessagingBotStatus.DISABLED
}
