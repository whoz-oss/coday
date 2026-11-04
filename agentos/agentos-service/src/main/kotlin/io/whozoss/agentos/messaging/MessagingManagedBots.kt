package io.whozoss.agentos.messaging

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import mu.KLogging
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates the bots of agents through the bot manager of the instance, so that nobody copies a token (Telegram's
 * managed bots).
 *
 * 1. An admin of the agent's namespace asks for a creation link ([requestCreation]): it opens the bot manager
 *    with a single-use token.
 * 2. The manager receives the token: it remembers who asked, for which agent, and answers with a button that
 *    opens Telegram's bot creation, name and username suggested.
 * 3. The admin confirms in Telegram: the manager is told who created which bot ([MessagingInbound.BotManaged]),
 *    fetches the new bot's token, attaches the bot to the agent, and links the creator's account to it.
 *
 * The creator is matched by their account on the platform: the token of step 1 tied it to the AgentOS user and
 * the agent in step 2, so a bot created without it, or by someone else, is never attached. Both maps are in
 * memory, like [MessagingLinkTokens]: a restart between the steps only costs a new click. The rights are
 * checked again when the bot is created.
 *
 * A bot the manager already knows was given a new token or owner: its new token replaces the stored one.
 */
@Service
class MessagingManagedBots(
    private val bots: MessagingBotService,
    private val registry: MessagingBotRegistry,
    private val identities: MessagingIdentityService,
    private val agentConfigService: AgentConfigService,
    private val permissionService: PermissionService,
    private val drivers: MessagingDrivers,
    properties: MessagingProperties,
    private val clock: Clock,
) {
    private val ttl = Duration.ofMinutes(properties.linkTokenTtlMinutes)
    private val requests = SingleUseTokens<CreationRequest>(ttl, clock)

    /** Step 2 to 3: the request of each creator, by platform and account, until the bot is created. */
    private val awaiting = ConcurrentHashMap<String, AwaitedCreation>()
    private val random = SecureRandom()

    /**
     * Step 1: the link that opens the bot manager of [platform] to create the bot of [agentId] in [namespaceId],
     * for [userId], who has the right to give the agent a bot.
     *
     * @throws ConflictException when the instance has no running bot manager on [platform]
     */
    fun requestCreation(
        userId: UUID,
        namespaceId: UUID,
        agentId: UUID,
        platform: MessagingPlatform,
    ): MessagingLink {
        val manager =
            registry.manager(platform)
                ?: throw ConflictException("No $platform bot manager is running: a super-admin sets one in Settings, Messaging")
        val agent = bots.requireAgent(namespaceId, agentId)
        val issued =
            requests.issue(CreationRequest(userId = userId, namespaceId = namespaceId, agentId = agent.id, platform = platform)) {
                it.userId == userId && it.platform == platform
            }
        return MessagingLink(url = manager.linkUrl(issued.token), expiresAt = issued.expiresAt)
    }

    /** What the bot manager receives. Never throws: a failure is logged and, when possible, told to the sender. */
    fun handle(
        adapter: MessagingAdapter,
        inbound: MessagingInbound,
    ) {
        val manager = adapter as? MessagingManagerAdapter ?: return
        try {
            when (inbound) {
                is MessagingInbound.Text -> handleText(manager, inbound)
                is MessagingInbound.BotManaged -> handleBotManaged(manager, inbound)
                is MessagingInbound.Click -> manager.acknowledgeClick(inbound.clickId)
            }
        } catch (e: Exception) {
            logger.warn(e) { "[Messaging] the bot manager @${manager.bot.username} failed on an update from ${inbound.chatId}" }
            runCatching { manager.send(inbound.chatId, OutboundMessage(FAILURE_TEXT)) }
        }
    }

    private fun handleText(
        manager: MessagingManagerAdapter,
        inbound: MessagingInbound.Text,
    ) {
        val command = MessagingCommand.parse(inbound.text)
        when {
            !inbound.direct || inbound.sender.bot -> Unit
            command?.name == MessagingCommand.START && !command.argument.isNullOrBlank() ->
                offerCreation(manager, inbound, command.argument)
            else -> manager.send(inbound.chatId, OutboundMessage(HELP_TEXT))
        }
    }

    /** Step 2. */
    private fun offerCreation(
        manager: MessagingManagerAdapter,
        inbound: MessagingInbound.Text,
        token: String,
    ) {
        val request = requests.consume(token)?.takeIf { it.platform == manager.platform }
        val agent = request?.let { agentConfigService.findById(it.agentId) }
        when {
            request == null -> manager.send(inbound.chatId, OutboundMessage(EXPIRED_TEXT))
            agent == null -> manager.send(inbound.chatId, OutboundMessage("This agent no longer exists."))
            else -> {
                val now = clock.instant()
                awaiting.entries.removeIf { (_, awaited) -> awaited.expiresAt <= now }
                awaiting[creatorKey(manager.platform, inbound.sender.externalUserId)] =
                    AwaitedCreation(request = request, expiresAt = now.plus(ttl))
                manager.offerBotCreation(
                    inbound.chatId,
                    BotCreationOffer(
                        text =
                            "Let's create the bot of **${agent.name}**. Tap the button below, check the bot's name " +
                                "and username, and confirm: I give the bot to the agent right after.",
                        buttonLabel = "Create the bot of ${agent.name}",
                        suggestedName = agent.name,
                        suggestedUsername = suggestedUsername(agent.name),
                    ),
                )
            }
        }
    }

    private fun handleBotManaged(
        manager: MessagingManagerAdapter,
        inbound: MessagingInbound.BotManaged,
    ) {
        when (val known = bots.findByAccount(manager.platform, inbound.bot.accountId)) {
            null -> create(manager, inbound)
            else -> {
                bots.replaceToken(known, manager.managedBotToken(inbound.bot.accountId))
                logger.info { "[Messaging] the token of @${known.username} changed, the new one replaces it" }
            }
        }
    }

    /** Step 3. */
    private fun create(
        manager: MessagingManagerAdapter,
        inbound: MessagingInbound.BotManaged,
    ) {
        val request =
            awaiting
                .remove(creatorKey(manager.platform, inbound.sender.externalUserId))
                ?.takeIf { it.expiresAt > clock.instant() }
                ?.request
        when {
            request == null -> {
                logger.info { "[Messaging] @${inbound.bot.username} was created without a request from AgentOS, left alone" }
                manager.send(inbound.chatId, OutboundMessage(NOT_REQUESTED_TEXT))
            }
            !canAdminister(request) ->
                manager.send(inbound.chatId, OutboundMessage("You can no longer give a bot to this agent."))
            else -> {
                val bot =
                    bots.attach(
                        namespaceId = request.namespaceId,
                        agentId = request.agentId,
                        platform = manager.platform,
                        token = manager.managedBotToken(inbound.bot.accountId),
                        managed = true,
                    )
                // The creator's private chat with the new bot has their id, as their chat with the manager does.
                identities.link(
                    request.userId,
                    LinkedAccount(
                        platform = bot.platform,
                        accountId = bot.accountId,
                        externalUserId = inbound.sender.externalUserId,
                        directChatId = inbound.chatId,
                        displayName = inbound.sender.displayName,
                    ),
                )
                val agentName = agentConfigService.findById(request.agentId)?.name ?: "your agent"
                val url = drivers.get(bot.platform).botUrl(bot.username)
                manager.send(
                    inbound.chatId,
                    OutboundMessage(
                        text =
                            "✓ **@${bot.username}** is ready, and your account is linked to it. " +
                                "Open it and say hello to **$agentName**.",
                        buttons = listOf(MessagingButton("Open @${bot.username}", MessagingAction.OpenUrl(url))),
                    ),
                )
                logger.info { "[Messaging] @${bot.username} created for agent $agentName by user ${request.userId}" }
            }
        }
    }

    /**
     * A username Telegram may accept: from the agent's name, with a random part, since usernames are global and
     * the obvious ones taken. 5 to 32 characters of `a-z 0-9 _`, starting with a letter, ending with `bot`.
     */
    private fun suggestedUsername(agentName: String): String {
        val slug =
            agentName
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(MAX_SLUG_LENGTH)
                .trimEnd('_')
                .let { if (it.firstOrNull()?.isLetter() == true) it else "agent_$it".trimEnd('_') }
        val suffix = (1..SUFFIX_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "${slug}_${suffix}_bot"
    }

    private fun canAdminister(request: CreationRequest): Boolean =
        permissionService.hasPermission(
            userId = request.userId.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = request.namespaceId.toString(),
            action = Action.WRITE,
        )

    private fun creatorKey(
        platform: MessagingPlatform,
        externalUserId: String,
    ): String = "$platform:$externalUserId"

    private data class CreationRequest(
        val userId: UUID,
        val namespaceId: UUID,
        val agentId: UUID,
        val platform: MessagingPlatform,
    )

    private data class AwaitedCreation(
        val request: CreationRequest,
        val expiresAt: Instant,
    )

    companion object : KLogging() {
        private const val MAX_SLUG_LENGTH = 20
        private const val SUFFIX_LENGTH = 4
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        private const val FAILURE_TEXT = "Something went wrong on the AgentOS side. Please try again."
        private const val EXPIRED_TEXT =
            "This link has expired or was already used. Ask for a new one on the agent's page in AgentOS."
        private const val NOT_REQUESTED_TEXT =
            "AgentOS did not ask for this bot, so I left it alone. To give an agent a bot, start from the agent's " +
                "page in AgentOS: Messaging, Create a bot."
        private const val HELP_TEXT =
            "I create the bots of AgentOS agents. Start from the agent's page in AgentOS: Messaging, Create a bot."
    }
}

/** A single-use link and when it stops working. */
data class MessagingLink(
    val url: String,
    val expiresAt: Instant,
)
