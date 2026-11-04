package io.whozoss.agentos.messaging

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.hamcrest.Matchers.startsWith
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The messaging controllers through the dispatcher: who may configure a bot, who may use one, the caller's
 * scope, and the error statuses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class, FakeMessagingConfiguration::class)
class MessagingControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var platform: FakeMessagingDriver
    @Autowired lateinit var bots: MessagingBotService
    @Autowired lateinit var identities: MessagingIdentityService
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var driver: Driver

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private lateinit var ada: User
    private lateinit var bob: User
    private lateinit var root: User
    private lateinit var namespace: Namespace
    private lateinit var helper: AgentConfig

    private fun user(
        name: String,
        admin: Boolean = false,
    ): User {
        val id = UUID.randomUUID()
        return users.save(User(metadata = EntityMetadata(id = id), externalId = "$name-$id@example.com", firstname = name, isAdmin = admin))
    }

    private fun actAs(user: User) {
        every { userService.getCurrentUser() } returns user
    }

    private fun attachHelper(): MessagingBot = bots.attach(namespace.id, helper.id, MessagingPlatform.TELEGRAM, HELPER_TOKEN)

    private fun linkedIdentity(
        owner: User,
        bot: MessagingBot,
    ): MessagingIdentity =
        identities.link(owner.id, LinkedAccount(MessagingPlatform.TELEGRAM, bot.accountId, "tg-${owner.id}", "chat-${owner.id}", owner.firstname.orEmpty()))

    init {
        beforeEach {
            Neo4jContainerSupport.clearDatabase(driver)
            platform.reset()
            ada = user("ada")
            bob = user("bob")
            root = user("root", admin = true)
            listOf(ada, bob, root).forEach { every { userService.findById(it.id) } returns it }
            actAs(ada)
            namespace = namespaceService.create(Namespace(name = "Forge"))
            permissionService.grantPermission(ada.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            permissionService.grantPermission(bob.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.MEMBER)
            helper = agentConfigService.create(AgentConfig(namespaceId = namespace.id, name = "Helper", enabled = true))
            namespaceService.deployAgents(namespace.id, listOf(helper.id))
            platform.register(HELPER_TOKEN, accountId = "4242", username = "helper_bot")
        }

        afterEach {
            (bots.findAllAgentBots() + bots.findManagers()).forEach { bots.remove(it) }
        }

        "an admin of the namespace gives the agent a bot; the answer never carries the token" {
            val response =
                mockMvc
                    .perform(
                        post("/api/namespaces/${namespace.id}/agent-configs/${helper.id}/messaging-bots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"platform":"TELEGRAM","token":"$HELPER_TOKEN"}"""),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.username").value("helper_bot"))
                    .andExpect(jsonPath("$.agentName").value("Helper"))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.url").value("https://t.me/helper_bot"))
                    .andReturn()
                    .response.contentAsString

            response shouldNotContain HELPER_TOKEN.substringAfter(':')
            bots.findAgentBots(namespace.id, helper.id) shouldHaveSize 1
        }

        "a member of the namespace sees the agent's bots but cannot change them" {
            val bot = attachHelper()
            actAs(bob)

            mockMvc
                .perform(get("/api/namespaces/${namespace.id}/agent-configs/${helper.id}/messaging-bots"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].id").value(bot.id.toString()))
            mockMvc
                .perform(
                    post("/api/namespaces/${namespace.id}/agent-configs/${helper.id}/messaging-bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"platform":"TELEGRAM","token":"$HELPER_TOKEN"}"""),
                ).andExpect(status().isForbidden)
            mockMvc
                .perform(patch("/api/messaging/bots/${bot.id}").contentType(MediaType.APPLICATION_JSON).content("""{"enabled":false}"""))
                .andExpect(status().isForbidden)
            mockMvc.perform(delete("/api/messaging/bots/${bot.id}")).andExpect(status().isForbidden)
        }

        "an admin turns a bot off and removes it" {
            val bot = attachHelper()

            mockMvc
                .perform(patch("/api/messaging/bots/${bot.id}").contentType(MediaType.APPLICATION_JSON).content("""{"enabled":false}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.status").value("DISABLED"))
            mockMvc.perform(delete("/api/messaging/bots/${bot.id}")).andExpect(status().isNoContent)

            bots.findById(bot.id) shouldBe null
        }

        "a token the platform refuses is a bad request" {
            mockMvc
                .perform(
                    post("/api/namespaces/${namespace.id}/agent-configs/${helper.id}/messaging-bots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"platform":"TELEGRAM","token":"1:refused-refused-refused-refused-x"}"""),
                ).andExpect(status().isBadRequest)
        }

        "a user lists the bots of the agents they can use, and links an account through one" {
            attachHelper()
            actAs(bob)

            mockMvc
                .perform(get("/api/messaging/bots"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].linked").value(false))
            val botId = bots.findAgentBots(namespace.id, helper.id).single().id
            mockMvc
                .perform(post("/api/messaging/bots/$botId/link"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.url").value(startsWith("https://t.me/helper_bot?start=")))
        }

        "a user who cannot use the agent does not see its bot" {
            val bot = attachHelper()
            val stranger = user("eve")
            every { userService.findById(stranger.id) } returns stranger
            actAs(stranger)

            mockMvc.perform(get("/api/messaging/bots")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
            mockMvc.perform(post("/api/messaging/bots/${bot.id}/link")).andExpect(status().isNotFound)
        }

        "the caller sees and unlinks only their own accounts" {
            val bot = attachHelper()
            val mine = linkedIdentity(ada, bot)
            val bobs = linkedIdentity(bob, bot)

            mockMvc
                .perform(get("/api/messaging/identities"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine.id.toString()))
                .andExpect(jsonPath("$[0].botUsername").value("helper_bot"))
                .andExpect(jsonPath("$[0].agentName").value("Helper"))
            mockMvc.perform(delete("/api/messaging/identities/${bobs.id}")).andExpect(status().isNotFound)
            mockMvc.perform(delete("/api/messaging/identities/${mine.id}")).andExpect(status().isNoContent)

            identities.findByUser(ada.id) shouldBe emptyList()
            identities.findByUser(bob.id).single().id shouldBe bobs.id
        }

        "continuing a case in the chat needs the right to write in it, and a linked account" {
            val bot = attachHelper()
            val case = caseService.create(Case(namespaceId = namespace.id))
            val body = """{"botId":"${bot.id}"}"""

            // Bob is a member of the namespace: he may use its agent, not write in someone else's case.
            actAs(bob)
            mockMvc
                .perform(post("/api/cases/${case.id}/messaging/continue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden)
            actAs(ada)
            mockMvc
                .perform(post("/api/cases/${case.id}/messaging/continue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict)
            linkedIdentity(ada, bot)
            mockMvc
                .perform(post("/api/cases/${case.id}/messaging/continue").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent)

            platform.adapter("4242").shouldNotBeNull().sent.single().chatId shouldBe "chat-${ada.id}"
        }

        "the bot managers are a super-admin's" {
            platform.register(MANAGER_TOKEN, accountId = "9000", username = "agentos_manager_bot", canManageBots = true)
            val body = """{"platform":"TELEGRAM","token":"$MANAGER_TOKEN"}"""

            mockMvc.perform(put("/api/messaging/bot-managers").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
            mockMvc.perform(get("/api/messaging/capabilities")).andExpect(jsonPath("$.botCreation.length()").value(0))

            actAs(root)
            mockMvc
                .perform(put("/api/messaging/bot-managers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.username").value("agentos_manager_bot"))
            mockMvc.perform(get("/api/messaging/bot-managers")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))

            actAs(ada)
            mockMvc.perform(get("/api/messaging/capabilities")).andExpect(jsonPath("$.botCreation[0]").value("TELEGRAM"))
            mockMvc
                .perform(
                    post("/api/namespaces/${namespace.id}/agent-configs/${helper.id}/messaging-bots/creation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"platform":"TELEGRAM"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.url").value(startsWith("https://t.me/agentos_manager_bot?start=")))
        }

        "creating a bot with no manager is a conflict" {
            mockMvc
                .perform(
                    post("/api/namespaces/${namespace.id}/agent-configs/${helper.id}/messaging-bots/creation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"platform":"TELEGRAM"}"""),
                ).andExpect(status().isConflict)
        }
    }

    private companion object {
        const val HELPER_TOKEN = "4242:helper-token-helper-token-helper-token"
        const val MANAGER_TOKEN = "9000:manager-token-manager-token-manager-tok"
    }
}
