package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.messaging.LinkedAccount
import io.whozoss.agentos.messaging.MessagingBot
import io.whozoss.agentos.messaging.MessagingBotRepository
import io.whozoss.agentos.messaging.MessagingBotRole
import io.whozoss.agentos.messaging.MessagingConversation
import io.whozoss.agentos.messaging.MessagingConversationRepository
import io.whozoss.agentos.messaging.MessagingConversationService
import io.whozoss.agentos.messaging.MessagingIdentity
import io.whozoss.agentos.messaging.MessagingIdentityRepository
import io.whozoss.agentos.messaging.MessagingIdentityService
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jMessagingPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var botRepository: MessagingBotRepository
    @Autowired lateinit var identityRepository: MessagingIdentityRepository
    @Autowired lateinit var conversationRepository: MessagingConversationRepository
    @Autowired lateinit var identities: MessagingIdentityService
    @Autowired lateinit var conversations: MessagingConversationService
    @Autowired lateinit var driver: Driver

    private fun identity(
        externalUserId: String,
        userId: UUID,
    ) = MessagingIdentity(
        platform = MessagingPlatform.TELEGRAM,
        accountId = "4242",
        externalUserId = externalUserId,
        directChatId = externalUserId,
        displayName = "Ada",
        userId = userId,
    )

    private fun link(
        userId: UUID,
        externalUserId: String,
    ): MessagingIdentity = identities.link(userId, LinkedAccount(MessagingPlatform.TELEGRAM, "4242", externalUserId, externalUserId, "Ada"))

    private fun bot(
        accountId: String,
        agentId: UUID = UUID.randomUUID(),
        namespaceId: UUID = UUID.randomUUID(),
    ) = MessagingBot(
        platform = MessagingPlatform.TELEGRAM,
        accountId = accountId,
        username = "bot_$accountId",
        displayName = "Bot",
        url = "https://t.me/bot",
        token = "$accountId:secret-secret-secret-secret-secret",
        agentId = agentId,
        namespaceId = namespaceId,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "a bot round-trips, its token decrypted" {
            val bot = botRepository.save(bot("4242"))

            botRepository.findById(bot.id) shouldBe bot.copy(metadata = bot.metadata)
            botRepository.findByBotKey(bot.botKey)?.token shouldBe bot.token
            botRepository.findBySlotKey(bot.slotKey)?.id shouldBe bot.id
            botRepository.findByAgentId(bot.agentId!!).map { it.id } shouldBe listOf(bot.id)
        }

        "a Telegram bot saved before bots had a url reads with the url Telegram gives it" {
            val bot = botRepository.save(bot("4242"))
            driver.session().use { it.run("MATCH (b:MessagingBot {id: \$id}) REMOVE b.url", mapOf("id" to bot.id.toString())).consume() }

            botRepository.findAll().single().url shouldBe "https://t.me/bot_4242"
        }

        "a bot serves one agent, and an agent has one bot per platform and namespace" {
            val bot = botRepository.save(bot("4242"))

            shouldThrow<DataIntegrityViolationException> { botRepository.save(bot("4242")) }
            shouldThrow<DataIntegrityViolationException> { botRepository.save(bot("5151", bot.agentId!!, bot.namespaceId!!)) }
        }

        "an instance has one bot manager per platform" {
            botRepository.save(bot("9000").copy(role = MessagingBotRole.MANAGER, agentId = null, namespaceId = null))

            shouldThrow<DataIntegrityViolationException> {
                botRepository.save(bot("9001").copy(role = MessagingBotRole.MANAGER, agentId = null, namespaceId = null))
            }
        }

        "a removed bot is gone, token and all" {
            val bot = botRepository.save(bot("4242"))

            botRepository.delete(bot.id) shouldBe true

            botRepository.findById(bot.id) shouldBe null
            botRepository.delete(bot.id) shouldBe false
        }

        "an identity and a conversation round-trip" {
            val identity = identityRepository.save(identity("11", UUID.randomUUID()))
            val caseId = UUID.randomUUID()
            val conversation =
                conversationRepository.save(
                    MessagingConversation(
                        platform = MessagingPlatform.TELEGRAM,
                        accountId = "4242",
                        chatId = "11",
                        userId = identity.userId,
                        caseId = caseId,
                        lastInboundMessageId = 7,
                    ),
                )

            identityRepository.findByIdentityKey(identity.identityKey) shouldBe identity
            conversationRepository.findByConversationKey(conversation.conversationKey) shouldBe conversation
            conversationRepository.findByCaseId(caseId) shouldContainExactly listOf(conversation)
        }

        "an account cannot be linked twice at once: the constraint holds on the active identities" {
            identityRepository.save(identity("11", UUID.randomUUID()))

            shouldThrow<DataIntegrityViolationException> { identityRepository.save(identity("11", UUID.randomUUID())) }
        }

        "a removed identity frees its account for a new link" {
            val first = identityRepository.save(identity("11", UUID.randomUUID()))
            identityRepository.delete(first.id)

            val second = identityRepository.save(identity("11", UUID.randomUUID()))

            identityRepository.findByIdentityKey(second.identityKey) shouldBe second
        }

        "linking again moves the account to the new user, and replaces the user's previous account" {
            val ada = UUID.randomUUID()
            val bob = UUID.randomUUID()
            link(ada, "11")

            link(bob, "11")
            link(bob, "12")

            identities.findByUser(ada) shouldBe emptyList()
            identities.findByUser(bob).map { it.externalUserId } shouldBe listOf("12")
        }

        "a chat opened twice at once has one conversation" {
            val userId = UUID.randomUUID()

            val opened =
                withContext(Dispatchers.IO) {
                    (1..4)
                        .map { async { conversations.open(MessagingPlatform.TELEGRAM, "4242", "11", userId) } }
                        .awaitAll()
                }

            opened.map { it.id }.toSet().size shouldBe 1
        }

        "a chat whose account changed hands starts afresh for the new owner" {
            val previous = conversations.open(MessagingPlatform.TELEGRAM, "4242", "11", UUID.randomUUID())
            conversations.update(previous) { it.copy(caseId = UUID.randomUUID()) }
            val newOwner = UUID.randomUUID()

            val reopened = conversations.open(MessagingPlatform.TELEGRAM, "4242", "11", newOwner)

            reopened.id shouldBe previous.id
            reopened.userId shouldBe newOwner
            reopened.caseId shouldBe null
        }

        "a stale copy of a conversation is updated from a fresh one" {
            val conversation = conversations.open(MessagingPlatform.TELEGRAM, "4242", "11", UUID.randomUUID())
            val caseId = UUID.randomUUID()
            conversations.update(conversation) { it.copy(caseId = caseId) }

            conversations.update(conversation) { it.copy(lastInboundMessageId = 9) }

            conversations.findById(conversation.id)?.let { (it.caseId to it.lastInboundMessageId) } shouldBe (caseId to 9L)
        }
    }
}
