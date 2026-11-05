package io.whozoss.agentos.messaging

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
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
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.messagingConversationId
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.configAgent.ConfigToolGrantService
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
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
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
 * moving the chat between cases, applying or rejecting a configuration plan from its card.
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
    @Autowired lateinit var delivery: MessagingDelivery
    @Autowired lateinit var configToolGrantService: ConfigToolGrantService

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

    private fun click(
        action: MessagingAction,
        from: String = chatId,
    ) = gateway.handle(bot, MessagingInbound.Click(chatId = from, sender = sender(from), clickId = "click-1", messageId = "77", action = action))

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

    /**
     * A case of Ada's that the chat follows, as after a message sent from it. Saved without a runtime, so that the
     * runtime the answer needs is rebuilt with the plan's question in it: a question is otherwise stored by the
     * runtime that asks it.
     */
    private fun caseFollowedByTheChat(): Case =
        cases.save(Case(namespaceId = namespace.id, title = "Tidy Helper")).also { case ->
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            delivery.redirect(case.id, conversation().id)
        }

    /** Runs the config agent's propose tool for Ada in [case] and stores what its run would have: the plan, then its question. */
    private suspend fun proposeToAda(
        case: Case,
        description: String,
    ): UUID {
        val tool =
            configToolGrantService
                .grantTools(mapOf(ConfigAgentIntegration.TYPE to null), agentName = "config", namespaceId = namespace.id, userId = ada.id, caseId = case.id)
                .single { it.name == ConfigAgentIntegration.PROPOSE_TOOL_NAME }
        val input =
            """
            { "summary": "Describe Helper",
              "operations": [ { "ref": "h", "kind": "AGENT", "action": "UPDATE", "id": "${agent.id}",
                                "changes": [ { "path": "/description", "value": "$description" } ] } ] }
            """.trimIndent()
        val interrupt =
            try {
                val result = tool.executeWithJson(input, ToolContext(namespaceId = namespace.id, userId = ada.id, userExternalId = null, caseEvents = emptyList()))
                fail("The proposal was not submitted: ${result.output}")
            } catch (e: AgentInterrupt.AwaitAnswer) {
                e
            }
        caseEventService.create(
            ToolResponseEvent(
                namespaceId = namespace.id,
                caseId = case.id,
                toolRequestId = "call-${UUID.randomUUID()}",
                toolName = ConfigAgentIntegration.PROPOSE_TOOL_NAME,
                output = MessageContent.Text("Waiting for the user's answer."),
                structuredOutput = interrupt.structuredOutput,
            ),
        )
        caseEventService.create(
            QuestionEvent(
                metadata = EntityMetadata(id = interrupt.questionId),
                namespaceId = namespace.id,
                caseId = case.id,
                agentId = UUID.randomUUID(),
                agentName = "config",
                question = interrupt.question,
                userId = interrupt.userId,
            ),
        )
        // The card goes out on the chat's lane: wait for it, so that a test reads only what follows.
        eventually(5.seconds) {
            withClue({ "sent: ${bot.sent}" }) { bot.sent.any { it.message.buttons.isNotEmpty() || "proposes" in it.message.text } shouldBe true }
        }
        return interrupt.questionId
    }

    private fun answersOf(caseId: UUID): List<AnswerEvent> = caseEventService.findByParent(caseId).filterIsInstance<AnswerEvent>()

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

        "a link message links the account that sends it, where a link cannot carry the token" {
            text(MessagingCommand.linkMessage(identities.issueLinkToken(ada.id, bot.bot).token))

            identities.findByAccount(MessagingPlatform.TELEGRAM, bot.accountId, chatId)?.userId shouldBe ada.id
        }

        "a command may start with an exclamation mark, for the platforms that keep the slash" {
            linkAda()
            text("first")
            val first = conversation().caseId

            text("!new")

            conversation().caseId shouldBe null
            first shouldNotBe null
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
            // The endpoint lets only a user who may write to the case continue it.
            permissionService.grantPermission(ada.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
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
            listOf(case, elsewhere).forEach {
                permissionService.grantPermission(ada.id.toString(), EntityType.CASE, it.id.toString(), PermissionRelation.ADMIN)
            }

            shouldThrow<ConflictException> { gateway.continueCaseIn(bot.bot.id, case.id, ada) }
            linkAda()
            shouldThrow<ConflictException> { gateway.continueCaseIn(bot.bot.id, elsewhere.id, ada) }
        }

        "a plan the agent proposes in a case the chat follows comes as its card, and Apply applies it with the user's rights" {
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            linkAda()
            val case = caseFollowedByTheChat()

            val questionId = proposeToAda(case, description = "Helps with the forge")

            eventually(2.seconds) {
                val card = bot.sent.single().message
                card.text shouldContain "config proposes a configuration change"
                card.text shouldContain "**Update agent Helper**"
                card.buttons.map { it.action } shouldContain MessagingAction.ApplyPlan(questionId)
            }
            bot.clear()

            click(MessagingAction.ApplyPlan(questionId))

            agentConfigService.findById(agent.id)!!.description shouldBe "Helps with the forge"
            bot.acknowledged.single().second shouldBe "✓ Applied"
            bot.buttonsRemoved shouldContain "77"
            bot.textsTo(chatId).first() shouldContain "✅ **Plan applied** by Ada Lovelace\n1. Update agent Helper: done"
            answersOf(case.id).single().let { answer ->
                answer.questionId shouldBe questionId
                answer.actor.id shouldBe ada.id.toString()
                answer.answer shouldContain "APPLIED by Ada Lovelace"
            }
        }

        "Apply writes with the user's own rights: a member who may not change the agent gets nothing written" {
            linkAda()
            val case = caseFollowedByTheChat()
            val questionId = proposeToAda(case, description = "Changed by a member")
            bot.clear()

            click(MessagingAction.ApplyPlan(questionId))

            agentConfigService.findById(agent.id)!!.description shouldNotBe "Changed by a member"
            bot.textsTo(chatId).first().let { report ->
                report shouldContain "❌ **Plan not applied**"
                report shouldContain "refused"
            }
        }

        "Reject answers the agent, and a plan already answered cannot be applied after" {
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            linkAda()
            val case = caseFollowedByTheChat()
            val questionId = proposeToAda(case, description = "Never applied")
            bot.clear()

            click(MessagingAction.RejectPlan(questionId))
            click(MessagingAction.ApplyPlan(questionId))

            agentConfigService.findById(agent.id)!!.description shouldNotBe "Never applied"
            answersOf(case.id).single().answer shouldContain "REJECTED by Ada Lovelace"
            bot.acknowledged.map { it.second } shouldBe listOf("✓ Rejected", "This plan was already applied or rejected.")
            bot.textsTo(chatId).first() shouldBe "✖️ **Plan rejected** by Ada Lovelace. The agent is told."
        }

        "a user who cannot read the case cannot apply its plan, even with the button" {
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            val case = caseFollowedByTheChat()
            val questionId = proposeToAda(case, description = "Applied by someone else")
            val bob = users.save(User(externalId = "bob-${UUID.randomUUID()}@example.com", firstname = "Bob"))
            permissionService.grantPermission(bob.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.MEMBER)
            text("/start ${identities.issueLinkToken(bob.id, bot.bot).token}", from = "2002")
            bot.clear()

            click(MessagingAction.ApplyPlan(questionId), from = "2002")

            agentConfigService.findById(agent.id)!!.description shouldNotBe "Applied by someone else"
            answersOf(case.id) shouldHaveSize 0
            bot.acknowledged.single().second shouldBe "You cannot change the plans of this case."
        }

        "someone who may write to the case but was not proposed the plan can neither apply it nor take the case's answers" {
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            val case = caseFollowedByTheChat()
            val questionId = proposeToAda(case, description = "Applied by a co-admin")
            val bob = users.save(User(externalId = "bob-${UUID.randomUUID()}@example.com", firstname = "Bob"))
            permissionService.grantPermission(bob.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            permissionService.grantPermission(bob.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            text("/start ${identities.issueLinkToken(bob.id, bot.bot).token}", from = "2002")
            bot.clear()

            click(MessagingAction.ApplyPlan(questionId), from = "2002")
            caseEventService.create(
                MessageEvent(
                    namespaceId = namespace.id,
                    caseId = case.id,
                    actor = Actor(id = "agent", displayName = "config", role = ActorRole.AGENT),
                    content = listOf(MessageContent.Text("Still waiting for Ada.")),
                ),
            )

            agentConfigService.findById(agent.id)!!.description shouldNotBe "Applied by a co-admin"
            bot.acknowledged.single().second shouldBe MessagingPlanCards.NOT_RECIPIENT_TEXT
            eventually(2.seconds) { bot.textsTo(chatId).any { "Still waiting for Ada." in it } shouldBe true }
            bot.textsTo("2002") shouldHaveSize 0
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
