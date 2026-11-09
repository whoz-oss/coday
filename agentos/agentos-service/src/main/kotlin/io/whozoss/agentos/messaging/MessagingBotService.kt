package io.whozoss.agentos.messaging

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigDeletedEvent
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceDeletedEvent
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import mu.KLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * The bots of agents and the bot managers of the instance: attaching one from its token, turning it on or off,
 * removing it. Every change is published as [MessagingBotChanged], which [MessagingBotSupervisor] applies at once.
 *
 * The token is checked with the platform before anything is saved ([MessagingDriver.probe]): a refused token,
 * or a bot already serving someone else, saves nothing. Rights are the caller's business: the controller checks
 * them, the bot manager checks them for the creator of a bot.
 *
 * An agent or a namespace deleted takes its bots with it: they would otherwise run on out of sight, holding
 * their tokens and their accounts.
 */
@Service
class MessagingBotService(
    private val repository: MessagingBotRepository,
    private val drivers: MessagingDrivers,
    private val agentConfigService: AgentConfigService,
    private val identities: MessagingIdentityService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun findById(id: UUID): MessagingBot? = repository.findById(id)

    /** The bot of the account [accountId] of [platform], whatever it serves. */
    fun findByAccount(
        platform: MessagingPlatform,
        accountId: String,
    ): MessagingBot? = repository.findByBotKey(MessagingBot.botKey(platform, accountId))

    /** The bots of [agentId] in [namespaceId], one per platform at most. */
    fun findAgentBots(
        namespaceId: UUID,
        agentId: UUID,
    ): List<MessagingBot> = repository.findByAgentId(agentId).filter { it.namespaceId == namespaceId }

    /** Every bot of an agent, in every namespace. */
    fun findAllAgentBots(): List<MessagingBot> = repository.findByRole(MessagingBotRole.AGENT)

    /** The bots of the agents of [namespaceId]. */
    fun findAgentBots(namespaceId: UUID): List<MessagingBot> = repository.findAgentBotsByNamespace(namespaceId)

    fun findManagers(): List<MessagingBot> = repository.findByRole(MessagingBotRole.MANAGER)

    /**
     * The agent [agentId] when it can answer in [namespaceId]: an agent of the namespace, or a platform agent.
     * Whether a given user may talk to it is checked when they do.
     *
     * @throws ResourceNotFoundException otherwise
     */
    fun requireAgent(
        namespaceId: UUID,
        agentId: UUID,
    ): AgentConfig =
        agentConfigService
            .findById(agentId)
            ?.takeIf { it.namespaceId == namespaceId || it.namespaceId == null }
            ?: throw ResourceNotFoundException("Agent $agentId does not answer in namespace $namespaceId")

    /**
     * The agent of [bot] when [userId] may talk to it, as the scheduler decides it: the agent is enabled and
     * deployed to the user in the bot's namespace. Null otherwise, or when the agent is gone.
     */
    fun usableAgent(
        userId: UUID,
        bot: MessagingBotRef,
    ): AgentConfig? {
        val agent = bot.agentId?.let { agentConfigService.findById(it) } ?: return null
        val namespaceId = bot.namespaceId ?: return null
        return agent.takeIf {
            it.enabled &&
                agentConfigService
                    .findDeployedByNamespaceIdAndUserIdAndName(namespaceId = namespaceId, userId = userId, agentName = it.name)
                    .any { deployed -> deployed.id == it.id }
        }
    }

    /**
     * Gives [agentId] the bot of [token] on [platform], replacing the agent's bot there; [appToken] is the second
     * token of a platform that needs one (Slack). Attaching the agent's own bot again only updates its tokens, and
     * keeps the links of its users.
     *
     * @throws BadRequestException when the platform refuses the token
     * @throws ConflictException when the bot already serves another agent or is a bot manager
     */
    fun attach(
        namespaceId: UUID,
        agentId: UUID,
        platform: MessagingPlatform,
        token: String,
        appToken: String? = null,
        managed: Boolean = false,
    ): MessagingBot {
        val agent = requireAgent(namespaceId, agentId)
        val probed = drivers.get(platform).probe(token.trim(), appToken?.trim()?.ifEmpty { null })
        val slotKey = MessagingBot.agentSlotKey(platform, namespaceId, agent.id)
        findByAccount(platform, probed.accountId)
            ?.takeIf { it.slotKey != slotKey }
            ?.let { other -> throw ConflictException("@${probed.username} already serves ${describe(other)}") }
        val previous = repository.findBySlotKey(slotKey)
        val bot =
            when {
                previous != null && previous.accountId == probed.accountId ->
                    previous.copy(
                        token = token.trim(),
                        appToken = appToken?.trim()?.ifEmpty { null },
                        username = probed.username,
                        displayName = probed.displayName,
                        url = probed.url,
                    )
                else -> {
                    previous?.let { remove(it) }
                    MessagingBot(
                        platform = platform,
                        accountId = probed.accountId,
                        username = probed.username,
                        displayName = probed.displayName,
                        url = probed.url,
                        token = token.trim(),
                        appToken = appToken?.trim()?.ifEmpty { null },
                        agentId = agent.id,
                        namespaceId = namespaceId,
                        managed = managed,
                    )
                }
            }
        return save(bot).also {
            logger.info { "[Messaging] $platform bot @${it.username} attached to agent ${agent.name} in $namespaceId" }
        }
    }

    /** Turns [bot] on or off. Off, it receives nothing; its users' links are kept. */
    fun setEnabled(
        bot: MessagingBot,
        enabled: Boolean,
    ): MessagingBot = save(bot.copy(enabled = enabled))

    /** Replaces the token of [bot], after the platform issued a new one (a managed bot whose token changed). */
    fun replaceToken(
        bot: MessagingBot,
        token: String,
    ): MessagingBot = save(bot.copy(token = token))

    /** Removes [bot] with its token and the links made through it. */
    fun remove(bot: MessagingBot) {
        repository.delete(bot.id)
        val unlinked = identities.unlinkAll(bot.platform, bot.accountId)
        eventPublisher.publishEvent(MessagingBotChanged.Removed(bot.id))
        logger.info { "[Messaging] ${bot.platform} bot @${bot.username} removed, $unlinked link(s) with it" }
    }

    // After the deletion commits, in a transaction of its own: one joined there would never commit.
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onAgentDeleted(deleted: AgentConfigDeletedEvent) {
        repository.findByAgentId(deleted.agentConfigId).forEach(::remove)
    }

    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onNamespaceDeleted(deleted: NamespaceDeletedEvent) {
        repository.findAgentBotsByNamespace(deleted.namespaceId).forEach(::remove)
    }

    /**
     * Makes the bot of [token] the bot manager of [platform], replacing the previous one.
     *
     * @throws BadRequestException when the platform refuses the token or the bot cannot manage other bots
     * @throws ConflictException when the bot serves an agent
     */
    fun setManager(
        platform: MessagingPlatform,
        token: String,
    ): MessagingBot {
        val driver = drivers.get(platform)
        if (!driver.supportsBotManagers) throw BadRequestException("$platform has no bot managers")
        val probed = driver.probe(token.trim())
        if (!probed.canManageBots) {
            throw BadRequestException(
                "@${probed.username} cannot manage other bots: turn Bot Management Mode on for it in BotFather's Mini App",
            )
        }
        findByAccount(platform, probed.accountId)
            ?.takeIf { it.role != MessagingBotRole.MANAGER }
            ?.let { other -> throw ConflictException("@${probed.username} already serves ${describe(other)}") }
        val previous = repository.findBySlotKey(MessagingBot.managerSlotKey(platform))
        val manager =
            when {
                previous != null && previous.accountId == probed.accountId ->
                    previous.copy(token = token.trim(), username = probed.username, displayName = probed.displayName, url = probed.url)
                else -> {
                    previous?.let { remove(it) }
                    MessagingBot(
                        platform = platform,
                        role = MessagingBotRole.MANAGER,
                        accountId = probed.accountId,
                        username = probed.username,
                        displayName = probed.displayName,
                        url = probed.url,
                        token = token.trim(),
                    )
                }
            }
        return save(manager).also { logger.info { "[Messaging] $platform bot manager is now @${it.username}" } }
    }

    /** Removes the bot manager of [platform]. The bots it created stay. False when there was none. */
    fun removeManager(platform: MessagingPlatform): Boolean =
        repository.findBySlotKey(MessagingBot.managerSlotKey(platform))?.let { remove(it) } != null

    private fun save(bot: MessagingBot): MessagingBot =
        repository.save(bot).also { eventPublisher.publishEvent(MessagingBotChanged.Saved(it)) }

    private fun describe(bot: MessagingBot): String =
        when (bot.role) {
            MessagingBotRole.MANAGER -> "as the bot manager"
            MessagingBotRole.AGENT ->
                "the agent ${bot.agentId?.let { agentConfigService.findById(it)?.name } ?: bot.agentId}"
        }

    companion object : KLogging()
}
