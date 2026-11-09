package io.whozoss.agentos.messaging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Bots as configuration data: attached, turned off and removed at run time, the supervisor following at once;
 * and the creation of a bot through the bot manager, with no token copied.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class, FakeMessagingConfiguration::class)
class MessagingBotsIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var platform: FakeMessagingDriver
    @Autowired lateinit var slack: FakeSlackDriver
    @Autowired lateinit var bots: MessagingBotService
    @Autowired lateinit var repository: MessagingBotRepository
    @Autowired lateinit var registry: MessagingBotRegistry
    @Autowired lateinit var supervisor: MessagingBotSupervisor
    @Autowired lateinit var managedBots: MessagingManagedBots
    @Autowired lateinit var identities: MessagingIdentityService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var driver: Driver

    private lateinit var ada: User
    private lateinit var namespace: Namespace
    private lateinit var helper: AgentConfig

    private fun agentIn(
        namespace: Namespace,
        name: String,
    ): AgentConfig = agentConfigService.create(AgentConfig(namespaceId = namespace.id, name = name, enabled = true))

    private fun attachHelper(): MessagingBot = bots.attach(namespace.id, helper.id, MessagingPlatform.TELEGRAM, HELPER_TOKEN)

    private fun linkAdaTo(bot: MessagingBot) =
        identities.link(ada.id, LinkedAccount(MessagingPlatform.TELEGRAM, bot.accountId, "1001", "1001", "Ada"))

    /** The bot manager, which may manage other bots. */
    private fun setManager(): FakeMessagingAdapter {
        platform.register(MANAGER_TOKEN, accountId = "9000", username = "agentos_manager_bot", canManageBots = true)
        bots.setManager(MessagingPlatform.TELEGRAM, MANAGER_TOKEN)
        return platform.adapter("9000").shouldNotBeNull()
    }

    private fun startFrom(
        manager: FakeMessagingAdapter,
        link: String,
        from: String = "1001",
    ) = platform.receive(
        manager.accountId,
        MessagingInbound.Text(
            chatId = from,
            sender = MessagingSender(from, "Ada L.", bot = false),
            messageId = 1,
            text = "/start ${link.substringAfter("?start=")}",
            direct = true,
        ),
    )

    private fun created(
        manager: FakeMessagingAdapter,
        accountId: String,
        username: String,
        by: String = "1001",
    ) = platform.receive(
        manager.accountId,
        MessagingInbound.BotManaged(
            chatId = by,
            sender = MessagingSender(by, "Ada L.", bot = false),
            bot = ManagedBotInfo(accountId = accountId, username = username, displayName = "Helper"),
        ),
    )

    init {
        beforeEach {
            Neo4jContainerSupport.clearDatabase(driver)
            platform.reset()
            slack.reset()
            ada = users.save(User(externalId = "ada-${UUID.randomUUID()}@example.com", firstname = "Ada"))
            namespace = namespaceService.create(Namespace(name = "Forge"))
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            helper = agentIn(namespace, "Helper")
            platform.register(HELPER_TOKEN, accountId = "4242", username = "helper_bot")
        }

        afterEach {
            (bots.findAllAgentBots() + bots.findManagers()).forEach { bots.remove(it) }
        }

        "an attached bot runs at once, its token stored encrypted" {
            val bot = attachHelper()

            registry.status(bot.id) shouldBe MessagingBotStatus.RUNNING
            registry.adapter(bot.id).shouldNotBeNull().bot.agentId shouldBe helper.id
            repository.findById(bot.id)?.token shouldBe HELPER_TOKEN
            val stored =
                driver.session().use { session ->
                    session.run("MATCH (b:MessagingBot {id: \$id}) RETURN b.encryptedToken AS t", mapOf("id" to bot.id.toString())).single()["t"].asString()
                }
            stored shouldNotBe HELPER_TOKEN
            stored shouldNotContainToken HELPER_TOKEN
        }

        "a token the platform refuses saves nothing" {
            shouldThrow<BadRequestException> { bots.attach(namespace.id, helper.id, MessagingPlatform.TELEGRAM, "1:refused-refused-refused-refused-x") }

            repository.findAll() shouldHaveSize 0
        }

        "a bot serves one agent" {
            attachHelper()
            val reviewer = agentIn(namespace, "Reviewer")

            shouldThrow<ConflictException> { bots.attach(namespace.id, reviewer.id, MessagingPlatform.TELEGRAM, HELPER_TOKEN) }
                .message shouldContain "already serves the agent Helper"
        }

        "an agent of another namespace gets no bot here" {
            val elsewhere = agentIn(namespaceService.create(Namespace(name = "Other")), "Elsewhere")

            shouldThrow<io.whozoss.agentos.exception.ResourceNotFoundException> {
                bots.attach(namespace.id, elsewhere.id, MessagingPlatform.TELEGRAM, HELPER_TOKEN)
            }
        }

        "attaching the agent's bot again keeps it and its links; another bot replaces it and its links" {
            val first = attachHelper()
            linkAdaTo(first)

            bots.attach(namespace.id, helper.id, MessagingPlatform.TELEGRAM, HELPER_TOKEN).id shouldBe first.id
            identities.findByUser(ada.id) shouldHaveSize 1

            platform.register(OTHER_TOKEN, accountId = "5151", username = "helper2_bot")
            val second = bots.attach(namespace.id, helper.id, MessagingPlatform.TELEGRAM, OTHER_TOKEN)

            bots.findAgentBots(namespace.id, helper.id).map { it.id } shouldBe listOf(second.id)
            registry.adapter(first.id) shouldBe null
            identities.findByUser(ada.id) shouldHaveSize 0
        }

        "a bot turned off stops receiving, and starts again when turned on" {
            val bot = attachHelper()

            bots.setEnabled(bot, false)
            registry.status(bot.id) shouldBe MessagingBotStatus.DISABLED
            registry.adapter(bot.id) shouldBe null
            platform.adapter("4242") shouldBe null

            bots.setEnabled(bots.findById(bot.id)!!, true)
            registry.status(bot.id) shouldBe MessagingBotStatus.RUNNING
        }

        "a removed bot stops, and the links made through it go" {
            val bot = attachHelper()
            linkAdaTo(bot)

            bots.remove(bot)

            repository.findById(bot.id) shouldBe null
            registry.status(bot.id) shouldBe null
            identities.findByUser(ada.id) shouldHaveSize 0
        }

        "deleting an agent, or its namespace, removes its bot and the links made through it" {
            val bot = attachHelper()
            linkAdaTo(bot)

            agentConfigService.delete(helper.id)

            repository.findById(bot.id) shouldBe null
            registry.status(bot.id) shouldBe null
            identities.findByUser(ada.id) shouldHaveSize 0

            helper = agentIn(namespace, "Helper again")
            val again = attachHelper()

            namespaceService.delete(namespace.id)

            repository.findById(again.id) shouldBe null
        }

        "the bots of the database start with the application" {
            // Saved without the service: no change is published, as for the bots found at startup.
            val bot =
                repository.save(
                    MessagingBot(
                        platform = MessagingPlatform.TELEGRAM,
                        accountId = "4242",
                        username = "helper_bot",
                        displayName = "Helper",
                        url = "https://t.me/bot",
                        token = HELPER_TOKEN,
                        agentId = helper.id,
                        namespaceId = namespace.id,
                    ),
                )
            registry.status(bot.id) shouldBe null

            supervisor.startAll()

            registry.status(bot.id) shouldBe MessagingBotStatus.RUNNING
        }

        "a bot manager must be able to manage bots, and serves no agent" {
            platform.register(MANAGER_TOKEN, accountId = "9000", username = "agentos_manager_bot", canManageBots = false)
            shouldThrow<BadRequestException> { bots.setManager(MessagingPlatform.TELEGRAM, MANAGER_TOKEN) }
                .message shouldContain "Bot Management Mode"

            setManager()

            registry.manager(MessagingPlatform.TELEGRAM).shouldNotBeNull().bot.username shouldBe "agentos_manager_bot"
            shouldThrow<ConflictException> { bots.attach(namespace.id, helper.id, MessagingPlatform.TELEGRAM, MANAGER_TOKEN) }
        }

        "the username suggested for an agent whose name starts with a digit stays within Telegram's 32 characters" {
            val manager = setManager()
            val reporting = agentIn(namespace, "2024 quarterly reporting assistant")

            startFrom(manager, managedBots.requestCreation(ada.id, namespace.id, reporting.id, MessagingPlatform.TELEGRAM).url)

            manager.offers.single().second.suggestedUsername shouldMatch Regex("^agent_2024_quarterly_[a-z0-9]{4}_bot$")
        }

        "creating a bot through the manager attaches it to the agent and links its creator" {
            val manager = setManager()
            val link = managedBots.requestCreation(ada.id, namespace.id, helper.id, MessagingPlatform.TELEGRAM)
            link.url shouldStartWith "https://t.me/agentos_manager_bot?start="

            startFrom(manager, link.url)

            val offer = manager.offers.single().second
            offer.suggestedName shouldBe "Helper"
            offer.suggestedUsername shouldMatch Regex("^helper_[a-z0-9]{4}_bot$")

            platform.register(NEW_TOKEN, accountId = "7777", username = "helper_x1y2_bot")
            manager.managedTokens["7777"] = NEW_TOKEN
            created(manager, accountId = "7777", username = "helper_x1y2_bot")

            val bot = bots.findAgentBots(namespace.id, helper.id).single()
            bot.accountId shouldBe "7777"
            bot.managed shouldBe true
            bot.token shouldBe NEW_TOKEN
            registry.status(bot.id) shouldBe MessagingBotStatus.RUNNING
            identities.findByAccount(MessagingPlatform.TELEGRAM, "7777", "1001")?.userId shouldBe ada.id
            manager.sent.last().message.let { ready ->
                ready.text shouldContain "@helper_x1y2_bot"
                ready.buttons.single().action shouldBe MessagingAction.OpenUrl("https://t.me/helper_x1y2_bot")
            }
        }

        "a bot created without a request from AgentOS is left alone" {
            val manager = setManager()
            manager.managedTokens["7777"] = NEW_TOKEN

            created(manager, accountId = "7777", username = "stray_bot")

            repository.findByRole(MessagingBotRole.AGENT) shouldHaveSize 0
            manager.textsTo("1001").single() shouldContain "AgentOS did not ask for this bot"
        }

        "a creation link works for the account that opened it only" {
            val manager = setManager()
            val link = managedBots.requestCreation(ada.id, namespace.id, helper.id, MessagingPlatform.TELEGRAM)
            startFrom(manager, link.url, from = "1001")
            manager.managedTokens["7777"] = NEW_TOKEN

            created(manager, accountId = "7777", username = "helper_x1y2_bot", by = "3003")

            repository.findByRole(MessagingBotRole.AGENT) shouldHaveSize 0
        }

        "the creator must still administer the namespace when the bot is created" {
            val manager = setManager()
            val link = managedBots.requestCreation(ada.id, namespace.id, helper.id, MessagingPlatform.TELEGRAM)
            startFrom(manager, link.url)
            permissionService.revokePermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            platform.register(NEW_TOKEN, accountId = "7777", username = "helper_x1y2_bot")
            manager.managedTokens["7777"] = NEW_TOKEN

            created(manager, accountId = "7777", username = "helper_x1y2_bot")

            repository.findByRole(MessagingBotRole.AGENT) shouldHaveSize 0
            manager.textsTo("1001").last() shouldContain "can no longer give a bot"
        }

        "a new token of a managed bot replaces the stored one" {
            val manager = setManager()
            val bot = attachHelper()
            manager.managedTokens["4242"] = OTHER_TOKEN

            created(manager, accountId = "4242", username = "helper_bot")

            repository.findById(bot.id)?.token shouldBe OTHER_TOKEN
        }

        "a Slack bot needs its app-level token too, and keeps both tokens encrypted" {
            slack.register(SLACK_TOKEN, accountId = "T1.UBOT", username = "helper")

            shouldThrow<BadRequestException> { bots.attach(namespace.id, helper.id, MessagingPlatform.SLACK, SLACK_TOKEN) }
            val bot = bots.attach(namespace.id, helper.id, MessagingPlatform.SLACK, SLACK_TOKEN, appToken = SLACK_APP_TOKEN)

            registry.status(bot.id) shouldBe MessagingBotStatus.RUNNING
            repository.findById(bot.id)?.appToken shouldBe SLACK_APP_TOKEN
            val stored =
                driver.session().use { session ->
                    session.run("MATCH (b:MessagingBot {id: \$id}) RETURN b.encryptedAppToken AS t", mapOf("id" to bot.id.toString())).single()["t"].asString()
                }
            (SLACK_APP_TOKEN in stored) shouldBe false
            // A Telegram bot and a Slack bot for the same agent: one per platform.
            attachHelper()
            bots.findAgentBots(namespace.id, helper.id).map { it.platform }.toSet() shouldBe setOf(MessagingPlatform.SLACK, MessagingPlatform.TELEGRAM)
        }

        "Slack has no bot managers" {
            shouldThrow<BadRequestException> { bots.setManager(MessagingPlatform.SLACK, SLACK_TOKEN) }
        }

        "creating a bot needs a running bot manager" {
            shouldThrow<ConflictException> { managedBots.requestCreation(ada.id, namespace.id, helper.id, MessagingPlatform.TELEGRAM) }
        }
    }

    private infix fun String.shouldNotContainToken(token: String) = (token.substringAfter(':') in this) shouldBe false

    private companion object {
        const val HELPER_TOKEN = "4242:helper-token-helper-token-helper-token"
        const val OTHER_TOKEN = "5151:other-token-other-token-other-token-x"
        const val NEW_TOKEN = "7777:created-token-created-token-created-tok"
        const val MANAGER_TOKEN = "9000:manager-token-manager-token-manager-tok"
        const val SLACK_TOKEN = "xoxb-helper"
        const val SLACK_APP_TOKEN = "xapp-helper"
    }
}
