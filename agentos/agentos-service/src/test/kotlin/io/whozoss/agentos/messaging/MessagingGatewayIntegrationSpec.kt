package io.whozoss.agentos.messaging

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.messagingConversationId
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The gateway end to end, on the real services and the embedded Neo4j, with a fake platform in place of
 * Telegram: an agent's bot, linking, starting and following a case with that agent, answering a question,
 * moving the chat between cases.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class, FakeMessagingConfiguration::class)
class MessagingGatewayIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var gateway: MessagingGateway
    @Autowired lateinit var platform: FakeMessagingDriver
    @Autowired lateinit var bots: MessagingBotService
    @Autowired lateinit var identities: MessagingIdentityService
    @Autowired lateinit var conversations: MessagingConversationService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var cases: CaseRepository
    @Autowired lateinit var caseEventService: CaseEventService
    @Autowired lateinit var driver: Driver

    private val chatId = "1001"
    private var nextMessageId = 1L

    private lateinit var ada: User
    private lateinit var namespace: Namespace
    private lateinit var agent: AgentConfig
    private lateinit var bot: FakeMessagingAdapter

    private fun sender(externalUserId: String = chatId) = MessagingSender(externalUserId = externalUserId, displayName = "Ada L.", bot = false)

    private fun text(
        text: String,
        messageId: Long = nextMessageId++,
        from: String = chatId,
    ) = gateway.handle(bot, MessagingInbound.Text(chatId = from, sender = sender(from), messageId = messageId, text = text, direct = true))

    private fun click(action: MessagingAction) =
        gateway.handle(bot, MessagingInbound.Click(chatId = chatId, sender = sender(), clickId = "click-1", messageId = "77", action = action))

    private fun conversation(): MessagingConversation = conversations.open(MessagingPlatform.TELEGRAM, bot.accountId, chatId, ada.id)

    /** Links the chat's account to [ada], as the profile's "Connect" does. */
    private fun linkAda() {
        text("/start ${identities.issueLinkToken(ada.id, bot.bot).token}")
        bot.clear()
    }

    private fun agentIn(
        namespace: Namespace,
        name: String,
    ): AgentConfig =
        agentConfigService.create(AgentConfig(namespaceId = namespace.id, name = name, enabled = true)).also {
            namespaceService.deployAgents(namespace.id, listOf(it.id))
        }

    private fun messagesOf(caseId: UUID): List<MessageEvent> = caseEventService.findByParent(caseId).filterIsInstance<MessageEvent>()

    init {
        beforeEach {
            Neo4jContainerSupport.clearDatabase(driver)
            platform.reset()
            ada = users.save(User(externalId = "ada-${UUID.randomUUID()}@example.com", firstname = "Ada", lastname = "Lovelace"))
            namespace = namespaceService.create(Namespace(name = "Forge"))
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.MEMBER)
            agent = agentIn(namespace, "Helper")
            platform.register(TOKEN, accountId = "4242", username = "helper_bot")
            bots.attach(namespace.id, agent.id, MessagingPlatform.TELEGRAM, TOKEN)
            bot = platform.adapter("4242").shouldNotBeNull()
        }

        afterEach {
            // Let the runs a test started finish, then stop their runtimes: a run still going when the next test
            // clears the database fails in the background, and the failure surfaces in whatever spec runs next.
            eventually(10.seconds) { caseService.getAllActiveCases().none { it.isRunning() } shouldBe true }
            caseService.getAllActiveCases().forEach { runtime -> runCatching { caseService.killCase(runtime.id) } }
            bots.findAllAgentBots().forEach { bots.remove(it) }
        }

        "an account that is not linked is told how to link it, and nothing else happens" {
            text("hello")

            bot.textsTo(chatId).single() shouldContain "not linked to AgentOS"
            cases.findConcerningUser(ada.id) shouldHaveSize 0
        }

        "a link token links the account that sends it, once" {
            val token = identities.issueLinkToken(ada.id, bot.bot).token

            text("/start $token")
            text("/start $token")

            identities.findByAccount(MessagingPlatform.TELEGRAM, bot.accountId, chatId)?.userId shouldBe ada.id
            bot.textsTo(chatId)[0] shouldContain "linked to AgentOS as **Ada Lovelace**. Write to talk to **Helper**"
            bot.textsTo(chatId)[1] shouldContain "expired or was already used"
        }

        "a link token works only with the bot it was issued for" {
            val other = agentIn(namespace, "Reviewer")
            platform.register(OTHER_TOKEN, accountId = "5151", username = "reviewer_bot")
            bots.attach(namespace.id, other.id, MessagingPlatform.TELEGRAM, OTHER_TOKEN)
            val tokenOfTheOtherBot = identities.issueLinkToken(ada.id, platform.adapter("5151").shouldNotBeNull().bot).token

            text("/start $tokenOfTheOtherBot")

            identities.findByAccount(MessagingPlatform.TELEGRAM, bot.accountId, chatId) shouldBe null
            bot.textsTo(chatId).single() shouldContain "expired or was already used"
        }

        "a message starts a case of the bot's namespace, addressed to its agent, as the linked user" {
            linkAda()

            text("hello agent")

            val caseId = conversation().caseId.shouldNotBeNull()
            caseService.findById(caseId).shouldNotBeNull().namespaceId shouldBe namespace.id
            permissionService.hasPermission(ada.id.toString(), EntityType.CASE, caseId.toString(), Action.WRITE) shouldBe true
            val message = messagesOf(caseId).first()
            message.actor shouldBe Actor(id = ada.id.toString(), displayName = "Ada Lovelace", role = ActorRole.USER)
            message.content shouldBe listOf(MessageContent.Text("@Helper hello agent"))
            message.sessionContext?.messagingConversationId() shouldBe conversation().id
            eventually(5.seconds) {
                caseEventService.findByParent(caseId).filterIsInstance<AgentSelectedEvent>().first().agentName shouldBe agent.name
            }
        }

        "a case whose agent is already the bot's gets the next messages as they are" {
            linkAda()
            val case = cases.save(Case(namespaceId = namespace.id))
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            caseEventService.create(
                AgentSelectedEvent(
                    namespaceId = namespace.id,
                    caseId = case.id,
                    agentId = UUID.nameUUIDFromBytes(agent.name.toByteArray()),
                    agentName = agent.name,
                ),
            )
            conversations.update(conversation()) { it.copy(caseId = case.id) }

            text("second")

            messagesOf(case.id).filter { it.actor.role == ActorRole.USER }.single().content shouldBe listOf(MessageContent.Text("second"))
        }

        "a message delivered twice is taken in once" {
            linkAda()

            text("hello agent", messageId = 500)
            text("hello agent", messageId = 500)

            val caseId = conversation().caseId.shouldNotBeNull()
            messagesOf(caseId).filter { it.actor.role == ActorRole.USER } shouldHaveSize 1
        }

        "/new makes the next message start another case" {
            linkAda()
            text("first")
            val first = conversation().caseId

            text("/new")
            text("second")

            conversation().caseId.shouldNotBeNull() shouldNotBe first
            // A case being saved by its run is briefly unlisted (its namespace edge is rewritten apart).
            eventually(5.seconds) { cases.findConcerningUser(ada.id) shouldHaveSize 2 }
        }

        "a linked user who may not use the agent is told so, and nothing else happens" {
            val bob = users.save(User(externalId = "bob-${UUID.randomUUID()}@example.com", firstname = "Bob"))
            text("/start ${identities.issueLinkToken(bob.id, bot.bot).token}", from = "2002")
            bot.clear()

            text("hello", from = "2002")

            bot.textsTo("2002").single() shouldContain "You cannot talk to **Helper**"
            cases.findConcerningUser(bob.id) shouldHaveSize 0
        }

        "a click on an option answers the question as the user, once" {
            linkAda()
            // Saved without a runtime, so that the runtime the answer needs is rebuilt with the question in it,
            // as after a restart: a question is otherwise stored by the runtime that asks it.
            val case = cases.save(Case(namespaceId = namespace.id))
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            val question =
                caseEventService.create(
                    QuestionEvent(
                        namespaceId = namespace.id,
                        caseId = case.id,
                        agentId = agent.id,
                        agentName = agent.name,
                        question = "Which one?",
                        options = listOf("A", "B"),
                        questionType = QuestionType.SINGLE_CHOICE,
                    ),
                )

            click(MessagingAction.AnswerQuestion(question.id, 1))
            click(MessagingAction.AnswerQuestion(question.id, 0))

            val answers = caseEventService.findByParent(case.id).filterIsInstance<AnswerEvent>()
            answers.single().answer shouldBe "B"
            bot.acknowledged.map { it.second } shouldBe listOf("✓ B", "This question was already answered.")
            bot.buttonsRemoved shouldContain "77"
        }

        "a question for someone else cannot be answered from the chat" {
            linkAda()
            val case = caseService.create(Case(namespaceId = namespace.id))
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            val question =
                caseEventService.create(
                    QuestionEvent(
                        namespaceId = namespace.id,
                        caseId = case.id,
                        agentId = agent.id,
                        agentName = agent.name,
                        question = "Approve?",
                        options = listOf("Yes"),
                        questionType = QuestionType.SINGLE_CHOICE,
                        userId = UUID.randomUUID(),
                    ),
                )

            click(MessagingAction.AnswerQuestion(question.id, 0))

            caseEventService.findByParent(case.id).filterIsInstance<AnswerEvent>() shouldHaveSize 0
            bot.acknowledged.single().second shouldBe "This question is for someone else."
        }

        "/cases offers the user's cases of the bot's namespace, and a choice moves the chat to it" {
            linkAda()
            val case = caseService.create(Case(namespaceId = namespace.id, title = "Refactoring"))
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            val elsewhere = caseService.create(Case(namespaceId = namespaceService.create(Namespace(name = "Other")).id, title = "Elsewhere"))
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, elsewhere.id.toString(), PermissionRelation.ADMIN)

            text("/cases")
            click(MessagingAction.OpenCase(case.id))

            bot.sent.first().message.buttons shouldBe listOf(MessagingButton("Refactoring", MessagingAction.OpenCase(case.id)))
            conversation().caseId shouldBe case.id
        }

        "a case the user cannot write to cannot be opened from the chat" {
            linkAda()
            val case = caseService.create(Case(namespaceId = namespace.id, title = "Someone else's"))

            click(MessagingAction.OpenCase(case.id))

            conversation().caseId shouldBe null
            bot.acknowledged.single().second shouldBe "You can no longer write in this case."
        }

        "continuing a case in the chat moves the direct chat to it and posts its last answer" {
            linkAda()
            val case = caseService.create(Case(namespaceId = namespace.id, title = "Release notes"))
            caseEventService.create(
                MessageEvent(
                    namespaceId = namespace.id,
                    caseId = case.id,
                    actor = Actor(id = "agent", displayName = "Helper", role = ActorRole.AGENT),
                    content = listOf(MessageContent.Text("Here is the draft.")),
                ),
            )

            gateway.continueCaseIn(bot.bot.id, case.id, ada)

            conversation().caseId shouldBe case.id
            bot.textsTo(chatId).single().let { posted ->
                posted shouldStartWith "📁 **Release notes**"
                posted shouldContain "Here is the draft."
            }
        }

        "continuing a case needs a linked account, and a case of the bot's namespace" {
            val case = caseService.create(Case(namespaceId = namespace.id))
            val elsewhere = caseService.create(Case(namespaceId = namespaceService.create(Namespace(name = "Other")).id))

            shouldThrow<ConflictException> { gateway.continueCaseIn(bot.bot.id, case.id, ada) }
            linkAda()
            shouldThrow<ConflictException> { gateway.continueCaseIn(bot.bot.id, elsewhere.id, ada) }
        }

        "a message from a bot is ignored" {
            linkAda()

            gateway.handle(
                bot,
                MessagingInbound.Text(chatId = chatId, sender = sender().copy(bot = true), messageId = 900, text = "hi", direct = true),
            )

            bot.sent shouldHaveSize 0
        }
    }

    private companion object {
        const val TOKEN = "4242:helper-token-helper-token-helper-token"
        const val OTHER_TOKEN = "5151:reviewer-token-reviewer-token-reviewer"
    }
}
