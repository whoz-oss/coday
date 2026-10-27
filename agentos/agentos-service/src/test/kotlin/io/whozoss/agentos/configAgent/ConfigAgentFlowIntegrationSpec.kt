package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import io.kotest.assertions.fail
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.authSetting.ApiKeyAuthSetting
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The config agent end to end, on the real security chain and embedded Neo4j: the propose tool
 * freezes a plan, the card's endpoints show and apply it with the rights of the user who clicks,
 * and the server answers the question. Only the identity ([UserService]) and the answer delivery
 * ([CaseService], which would start a real agent run) are mocked; the mock stores the answer like the
 * real service, so a plan is closed once answered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class, ConfigAgentFlowIntegrationSpec.MovableClockConfiguration::class)
class ConfigAgentFlowIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var configToolGrantService: ConfigToolGrantService
    @Autowired lateinit var caseEventService: CaseEventService
    @Autowired lateinit var caseRepository: CaseRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository
    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var integrationConfigService: IntegrationConfigService
    @Autowired lateinit var promptService: PromptService
    @Autowired lateinit var authSettingService: AuthSettingService

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @MockkBean(relaxed = true) lateinit var caseService: CaseService

    /** The real writer, stubbed by a test that needs one write to be refused. */
    @SpykBean lateinit var configWriter: ConfigWriter

    /** The plan's expiry and the views read this clock, so a test can move time forward. */
    @Autowired lateinit var clock: MovableClock

    private lateinit var alice: User // ADMIN of the namespace, the user the agent runs for
    private lateinit var bob: User // may write in the case, nothing else
    private lateinit var carol: User // MEMBER of the namespace
    private lateinit var namespace: Namespace
    private lateinit var case: Case
    private lateinit var devAgent: AgentConfig
    private lateinit var suffix: String

    private fun user(isAdmin: Boolean = false): User {
        val id = UUID.randomUUID()
        return User(metadata = EntityMetadata(id = id), externalId = "cfg-$id@example.com", email = "cfg-$id@example.com", isAdmin = isAdmin)
    }

    private fun superAdmin(): User =
        user(isAdmin = true).also { root ->
            userRepository.save(root)
            every { userService.findById(root.id) } returns root
        }

    private fun actAs(user: User) {
        every { userService.getCurrentUser() } returns user
    }

    private fun configTool(
        caller: User,
        name: String,
    ): StandardTool<*> =
        configToolGrantService
            .grantTools(
                integrations = mapOf(ConfigAgentIntegration.TYPE to null),
                agentName = "config",
                namespaceId = namespace.id,
                userId = caller.id,
                caseId = case.id,
            ).single { it.name == name }

    private fun proposeTool(caller: User): StandardTool<*> = configTool(caller, ConfigAgentIntegration.PROPOSE_TOOL_NAME)

    /** Runs the read tool as [caller] and returns its JSON output, or fails the test with the tool's error. */
    private suspend fun read(
        caller: User,
        input: String,
    ): JsonNode {
        val result = configTool(caller, ConfigAgentIntegration.READ_TOOL_NAME).executeWithJson(input, toolContext(caller))
        result.success shouldBe true
        return objectMapper.readTree(result.output)
    }

    private fun toolContext(caller: User) = ToolContext(namespaceId = namespace.id, userId = caller.id, userExternalId = null, caseEvents = emptyList())

    /** Another namespace, administered by [admin] when given. */
    private fun otherNamespace(admin: User?): Namespace =
        namespaceService
            .create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "cfg-other-${UUID.randomUUID()}", name = "Other $suffix"))
            .also { other ->
                admin?.let { permissionService.grantPermission(it.id.toString(), EntityType.NAMESPACE, other.id.toString(), PermissionRelation.ADMIN) }
            }

    /** Runs the propose tool as [caller] and records what an agent run would have persisted. */
    private suspend fun propose(
        caller: User,
        input: String,
    ): AgentInterrupt.AwaitAnswer {
        val interrupt =
            try {
                fail("The proposal was not submitted: ${proposeTool(caller).executeWithJson(input, toolContext(caller)).output}")
            } catch (e: AgentInterrupt.AwaitAnswer) {
                e
            }
        val now = Instant.now()
        caseEventService.create(
            ToolResponseEvent(
                namespaceId = namespace.id,
                caseId = case.id,
                timestamp = now,
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
                timestamp = now.plusMillis(1),
                agentId = UUID.randomUUID(),
                agentName = "config",
                question = interrupt.question,
                userId = interrupt.userId,
            ),
        )
        return interrupt
    }

    private val samePlan: String
        get() =
            """
                    { "summary": "Plug Acme into the dev agent",
                      "operations": [
                        { "ref": "p1", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "triage-$suffix", "content": ["Triage this"], "agentConfigId": { "${'$'}op": "sup" } } },
                        { "ref": "dev", "kind": "AGENT", "action": "UPDATE", "id": "${devAgent.id}",
                          "changes": [ { "path": "/integrations/ACME_$suffix", "value": null } ] },
                        { "ref": "sup", "kind": "AGENT", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "support-$suffix", "instructions": "Help the user", "enabled": true }, "deployTo": "NAMESPACE" },
                        { "ref": "acme", "kind": "INTEGRATION", "action": "CREATE", "scope": { "namespaceId": "current", "userId": null },
                          "value": { "name": "ACME_$suffix", "integrationType": "JIRA", "description": "Acme" } }
                      ] }
                    """.trimIndent()

    /** A new namespace with an integration and an agent in it, and a platform agent deployed on it. */
    private fun namespacePlan(platformAgent: AgentConfig) =
        """
        { "summary": "Copy the setup into a new namespace",
          "operations": [
            { "ref": "dev", "kind": "AGENT", "action": "CREATE", "scope": { "namespaceId": { "${'$'}op": "ns" } },
              "value": { "name": "dev-copy-$suffix", "instructions": "Help the user", "enabled": true }, "deployTo": "NAMESPACE" },
            { "ref": "shared", "kind": "AGENT", "action": "DEPLOY", "id": "${platformAgent.id}", "scope": { "namespaceId": { "${'$'}op": "ns" } } },
            { "ref": "acme", "kind": "INTEGRATION", "action": "CREATE", "scope": { "namespaceId": { "${'$'}op": "ns" } },
              "value": { "name": "ACME_$suffix", "integrationType": "JIRA" } },
            { "ref": "ns", "kind": "NAMESPACE", "action": "CREATE", "value": { "name": "Copy $suffix", "description": "Copied" } }
          ] }
        """.trimIndent()

    private fun platformAgent(): AgentConfig =
        agentConfigRepository.save(AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, name = "shared-$suffix", enabled = true))

    private fun promptPlan(name: String) =
        """
        { "operations": [ { "ref": "p", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "current" },
          "value": { "name": "$name-$suffix", "content": ["Hello"] } } ] }
        """.trimIndent()

    private fun view(questionId: UUID): JsonNode =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/cases/${case.id}/config-changes/$questionId"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun apply(
        questionId: UUID,
        planHash: String,
        secrets: Map<String, String> = emptyMap(),
    ) = mockMvc.perform(
        post("/api/cases/${case.id}/config-changes/$questionId/apply")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("expectedPlanHash" to planHash, "secrets" to secrets))),
    )

    private fun applied(
        questionId: UUID,
        planHash: String,
        secrets: Map<String, String> = emptyMap(),
    ): JsonNode = objectMapper.readTree(apply(questionId, planHash, secrets).andExpect(status().isOk).andReturn().response.contentAsString)

    init {
        beforeEach {
            alice = user()
            bob = user()
            carol = user()
            listOf(alice, bob, carol).forEach { u ->
                userRepository.save(u)
                every { userService.findById(u.id) } returns u
            }
            suffix = UUID.randomUUID().toString().take(8)
            namespace = namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "cfg-ns-$suffix", name = "Config $suffix"))
            case = caseRepository.save(Case(namespaceId = namespace.id))
            permissionService.grantPermission(alice.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            permissionService.grantPermission(carol.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.MEMBER)
            permissionService.grantPermission(bob.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            permissionService.grantPermission(carol.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            devAgent =
                agentConfigRepository.save(
                    AgentConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespace.id,
                        name = "dev-$suffix",
                        enabled = true,
                        subAgents = listOf("helper-$suffix"),
                    ),
                )
            actAs(alice)
            clock.now = Instant.now()
            every { caseService.addMessage(any(), any(), any(), any(), any()) } answers {
                val questionId = arg<UUID?>(3)
                if (questionId != null) {
                    caseEventService.create(
                        AnswerEvent(
                            namespaceId = namespace.id,
                            caseId = firstArg(),
                            questionId = questionId,
                            actor = secondArg(),
                            answer = (thirdArg<List<MessageContent>>().first() as MessageContent.Text).content,
                        ),
                    )
                }
            }
        }

        "a namespace admin's plan is shown, applied in order with their rights and answered; proposing it again converges" {
            val proposal = propose(alice, samePlan)

            val shown = view(proposal.questionId)
            shown["state"].asText() shouldBe "OPEN"
            shown["canApply"].asBoolean() shouldBe true
            shown["operations"].map { it["ref"].asText() } shouldContainExactly listOf("acme", "sup", "dev", "p1")
            shown["operations"].map { it["status"].asText() }.toSet() shouldBe setOf("PENDING")

            actAs(bob)
            view(proposal.questionId)["canApply"].asBoolean() shouldBe false
            apply(proposal.questionId, shown["planHash"].asText()).andExpect(status().isForbidden)

            actAs(alice)
            apply(proposal.questionId, "sha256:not-the-plan").andExpect(status().isConflict)

            val report = applied(proposal.questionId, shown["planHash"].asText())
            report["decision"].asText() shouldBe "APPLIED"
            report["results"].map { it["status"].asText() }.toSet() shouldBe setOf("APPLIED")

            val support = agentConfigService.findByNamespace(namespace.id).single { it.name == "support-$suffix" }
            namespaceService.findDeployedAgentIds(namespace.id) shouldContain support.id
            integrationConfigService.findByNamespaceShared(namespace.id).map { it.name } shouldContain "ACME_$suffix"
            agentConfigService.findById(devAgent.id)!!.integrations!!.keys shouldContain "ACME_$suffix"
            promptService.findByScope(namespace.id, null, null).single { it.name == "triage-$suffix" }.agentConfigId shouldBe support.id

            val answer = slot<List<MessageContent>>()
            verify(exactly = 1) { caseService.addMessage(case.id, any(), capture(answer), proposal.questionId, any()) }
            val text = (answer.captured.single() as MessageContent.Text).content
            text shouldStartWith "[${ConfigAgentIntegration.TYPE} change"
            text shouldContain "APPLIED by"

            val after = view(proposal.questionId)
            after["state"].asText() shouldBe "ANSWERED"
            after["canApply"].asBoolean() shouldBe false
            after["operations"].map { it["status"].asText() }.toSet() shouldBe setOf("APPLIED", "ALREADY_APPLIED")
            apply(proposal.questionId, shown["planHash"].asText()).andExpect(status().isConflict)

            val again = propose(alice, samePlan)
            view(proposal.questionId)["state"].asText() shouldBe "ANSWERED"
            val reapplied = applied(again.questionId, view(again.questionId)["planHash"].asText())
            reapplied["results"].map { it["status"].asText() }.toSet() shouldBe setOf("ALREADY_APPLIED")
        }

        "a super-admin's plan creates a namespace and configures it, not the case's namespace; proposing it again converges" {
            val root = superAdmin()
            val shared = platformAgent()
            actAs(root)
            val proposal = propose(root, namespacePlan(shared))

            val shown = view(proposal.questionId)
            shown["operations"].map { it["ref"].asText() } shouldContainExactly listOf("ns", "acme", "dev", "shared")
            shown["operations"].map { it["status"].asText() }.toSet() shouldBe setOf("PENDING")
            shown["operations"].drop(1).map { it["namespaceName"].asText() }.toSet() shouldBe setOf("Copy $suffix")

            val report = applied(proposal.questionId, shown["planHash"].asText())

            report["decision"].asText() shouldBe "APPLIED"
            val created = namespaceService.findAll().single { it.name == "Copy $suffix" }
            integrationConfigService.findByNamespaceShared(created.id).map { it.name } shouldContainExactly listOf("ACME_$suffix")
            val dev = agentConfigService.findByNamespace(created.id).single { it.name == "dev-copy-$suffix" }
            namespaceService.findDeployedAgentIds(created.id) shouldBe setOf(dev.id, shared.id)
            agentConfigService.findByNamespace(namespace.id).map { it.name } shouldNotContain "dev-copy-$suffix"
            namespaceService.findDeployedAgentIds(namespace.id) shouldNotContain shared.id
            report["results"].single { it["ref"].asText() == "ns" }["entityId"].asText() shouldBe created.id.toString()

            val again = propose(root, namespacePlan(shared))
            val reapplied = applied(again.questionId, view(again.questionId)["planHash"].asText())
            reapplied["results"].map { it["status"].asText() }.toSet() shouldBe setOf("ALREADY_APPLIED")
            namespaceService.findAll().count { it.name == "Copy $suffix" } shouldBe 1
        }

        "a namespace admin's plan that creates a namespace is refused by the REST rules, and nothing after it runs" {
            val proposal = propose(alice, namespacePlan(platformAgent()))

            val report = applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())

            report["decision"].asText() shouldBe "FAILED"
            report["results"].associate { it["ref"].asText() to it["status"].asText() } shouldBe
                mapOf("ns" to "DENIED", "acme" to "NOT_RUN", "dev" to "NOT_RUN", "shared" to "NOT_RUN")
            namespaceService.findAll().none { it.name == "Copy $suffix" } shouldBe true
        }

        "a scope names this namespace, the platform or a namespace the same plan creates, nothing else" {
            val result =
                proposeTool(alice).executeWithJson(
                    """
                    { "operations": [
                        { "ref": "ns", "kind": "NAMESPACE", "action": "CREATE", "scope": { "namespaceId": "current" }, "value": { "name": "N-$suffix" } },
                        { "ref": "p", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": { "${'$'}op": "q" } },
                          "value": { "name": "P-$suffix", "content": ["x"] } },
                        { "ref": "q", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "Q-$suffix", "content": ["x"] } },
                        { "ref": "d", "kind": "AGENT", "action": "DEPLOY", "id": "${devAgent.id}", "scope": { "namespaceId": null } },
                        { "ref": "u", "kind": "NAMESPACE", "action": "ENABLE", "id": "${namespace.id}" } ] }
                    """.trimIndent(),
                    ToolContext(namespaceId = namespace.id, userId = alice.id, userExternalId = null, caseEvents = emptyList()),
                )

            result.errorType shouldBe "INVALID_PROPOSAL"
            result.output shouldContain "ns: a namespace has no scope"
            result.output shouldContain "p: scope.namespaceId {\"${'$'}op\": \"q\"} must name an operation of this plan that creates a NAMESPACE"
            result.output shouldContain "d: a deployment needs a namespace"
            result.output shouldContain "u: ENABLE is not supported for NAMESPACE"
        }

        "from this case, a namespace admin diagnoses a namespace they administer, then sets its default agent and enables it" {
            val other = otherNamespace(admin = alice)
            val helper =
                agentConfigRepository.save(
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = other.id, name = "helper-$suffix", enabled = false),
                )
            namespaceService.deployAgents(other.id, listOf(helper.id))

            read(alice, """{ "kind": "namespace", "id": "${other.id}" }""")["readiness"]["defaultAgentStatus"].asText() shouldBe "NOT_SET"
            read(alice, """{ "kind": "agent", "namespaceId": "${other.id}", "scope": "namespace" }""").map { it["name"].asText() } shouldContain
                "helper-$suffix"

            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "default", "kind": "NAMESPACE", "action": "UPDATE", "id": "${other.id}",
                          "changes": [ { "path": "/defaultAgentName", "value": "helper-$suffix" } ] },
                        { "ref": "enable", "kind": "AGENT", "action": "ENABLE", "id": "${helper.id}" } ] }
                    """.trimIndent(),
                )
            val shown = view(proposal.questionId)
            shown["operations"].map { it["ref"].asText() } shouldContainExactly listOf("enable", "default")
            shown["operations"].map { it["namespaceName"].asText() }.toSet() shouldBe setOf("Other $suffix")

            applied(proposal.questionId, shown["planHash"].asText())["decision"].asText() shouldBe "APPLIED"

            namespaceService.findById(other.id)!!.defaultAgentName shouldBe "helper-$suffix"
            val readiness = read(alice, """{ "kind": "namespace", "id": "${other.id}" }""")["readiness"]
            readiness["defaultAgentStatus"].asText() shouldBe "OK"
            readiness["resolvedAgent"].asText() shouldBe "helper-$suffix"
        }

        "the readiness of a namespace names the condition that keeps a message from reaching its default agent" {
            val other = otherNamespace(admin = alice)
            suspend fun status(): String = read(alice, """{ "kind": "namespace", "id": "${other.id}" }""")["readiness"]["defaultAgentStatus"].asText()
            namespaceService.update(other.copy(defaultAgentName = "ghost-$suffix"))
            status() shouldBe "NOT_FOUND"

            val helper =
                agentConfigRepository.save(
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = other.id, name = "ghost-$suffix", enabled = false),
                )
            status() shouldBe "DISABLED"

            agentConfigService.enable(helper.id)
            status() shouldBe "NOT_DEPLOYED"

            namespaceService.deployAgents(other.id, listOf(helper.id))
            status() shouldBe "OK"
        }

        "a namespace the user does not administer stays out of reach of the config tools" {
            val other = otherNamespace(admin = null)
            permissionService.grantPermission(alice.id.toString(), EntityType.NAMESPACE, other.id.toString(), PermissionRelation.MEMBER)
            val stranger =
                agentConfigRepository.save(
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = other.id, name = "stranger-$suffix", enabled = true),
                )

            configTool(alice, ConfigAgentIntegration.READ_TOOL_NAME)
                .executeWithJson("""{ "kind": "agent", "namespaceId": "${other.id}" }""", toolContext(alice))
                .errorType shouldBe "INVALID_INPUT"
            val refused =
                proposeTool(alice).executeWithJson(
                    """
                    { "operations": [
                        { "ref": "p", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "${other.id}" },
                          "value": { "name": "P-$suffix", "content": ["x"] } },
                        { "ref": "e", "kind": "AGENT", "action": "ENABLE", "id": "${stranger.id}" } ] }
                    """.trimIndent(),
                    toolContext(alice),
                )

            refused.errorType shouldBe "INVALID_PROPOSAL"
            refused.output shouldContain "p: scope.namespaceId must be this namespace"
            refused.output shouldContain "e: no agent with id ${stranger.id} is visible to the user"
        }

        "an integration is copied by the server into an administered namespace, its parameters never reaching the agent" {
            val other = otherNamespace(admin = alice)
            val source =
                integrationConfigService.create(
                    IntegrationConfig(
                        namespaceId = namespace.id,
                        userId = null,
                        name = "SRC_$suffix",
                        integrationType = "JIRA",
                        description = "Source",
                        parameters =
                            objectMapper.readTree(
                                """{ "url": "https://jira.example.com", "command": "npx", "args": ["-y", "pkg"], "token": "t0k3n-$suffix", "project": "OLD" }""",
                            ),
                    ),
                )
            val proposalJson =
                """
                { "operations": [
                    { "ref": "copy", "kind": "INTEGRATION", "action": "CREATE", "copyFrom": "${source.metadata.id}",
                      "scope": { "namespaceId": "${other.id}" }, "value": { "parameters": { "project": "NEW" } } } ] }
                """.trimIndent()
            val proposal = propose(alice, proposalJson)
            val plan = objectMapper.writeValueAsString(proposal.structuredOutput)
            plan shouldNotContain "t0k3n-$suffix"
            plan shouldNotContain "npx"
            view(proposal.questionId)["operations"].single()["copyFrom"].asText() shouldBe "SRC_$suffix"

            applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())["decision"].asText() shouldBe "APPLIED"

            view(proposal.questionId)["operations"].single()["status"].asText() shouldBe "ALREADY_APPLIED"
            val again = propose(alice, proposalJson)
            applied(again.questionId, view(again.questionId)["planHash"].asText())["results"].single()["status"].asText() shouldBe "ALREADY_APPLIED"
            val copy = integrationConfigService.findByNamespaceShared(other.id).single { it.name == "SRC_$suffix" }
            copy.integrationType shouldBe "JIRA"
            copy.description shouldBe "Source"
            copy.parameters shouldBe
                objectMapper.readTree(
                    """{ "url": "https://jira.example.com", "command": "npx", "args": ["-y", "pkg"], "token": "t0k3n-$suffix", "project": "NEW" }""",
                )
        }

        "a number sent as an integer matches the decimal the server stores, and a removed field reads as applied" {
            val other = otherNamespace(admin = alice)
            namespaceService.update(other.copy(defaultAgentName = "someone-$suffix"))
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "ns", "kind": "NAMESPACE", "action": "UPDATE", "id": "${other.id}",
                          "changes": [ { "path": "/runCostThreshold", "value": 10 }, { "path": "/defaultAgentName", "remove": true } ] } ] }
                    """.trimIndent(),
                )

            applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())["decision"].asText() shouldBe "APPLIED"

            namespaceService.findById(other.id)!!.runCostThreshold shouldBe 10.0
            view(proposal.questionId)["operations"].single()["status"].asText() shouldBe "APPLIED"
        }

        "an existing entity with an empty field the plan sets is a conflict, not already applied" {
            val other = otherNamespace(admin = alice)
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "ns", "kind": "NAMESPACE", "action": "CREATE", "value": { "name": "Other $suffix", "defaultAgentName": "assistant" } } ] }
                    """.trimIndent(),
                )

            view(proposal.questionId)["operations"].single()["status"].asText() shouldBe "CONFLICT"
            namespaceService.findById(other.id)!!.defaultAgentName shouldBe null
        }

        "a secret declared under parameters does not hide the other parameters from the comparison" {
            integrationConfigService.create(
                IntegrationConfig(
                    namespaceId = namespace.id,
                    name = "JIRA_$suffix",
                    integrationType = "JIRA",
                    parameters = objectMapper.readTree("""{ "url": "https://old.example.com" }"""),
                ),
            )
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "jira", "kind": "INTEGRATION", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "JIRA_$suffix", "integrationType": "JIRA", "parameters": { "url": "https://new.example.com" } },
                          "secrets": [ { "path": "/parameters/token", "label": "Jira token" } ] } ] }
                    """.trimIndent(),
                )

            view(proposal.questionId)["operations"].single()["status"].asText() shouldBe "CONFLICT"
        }

        "the readiness speaks for members: a super-admin reading it is told an undeployed agent does not answer, matched by prefix" {
            val root = superAdmin()
            val other = otherNamespace(admin = alice)
            namespaceService.update(other.copy(defaultAgentName = "assist"))
            val candidate =
                agentConfigRepository.save(
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = other.id, name = "assistant-$suffix", enabled = false),
                )
            suspend fun status(): String = read(root, """{ "kind": "namespace", "id": "${other.id}" }""")["readiness"]["defaultAgentStatus"].asText()

            status() shouldBe "DISABLED"
            agentConfigService.enable(candidate.id)
            status() shouldBe "NOT_DEPLOYED"
            namespaceService.deployAgents(other.id, listOf(candidate.id))
            status() shouldBe "OK"
        }

        "a DTO the REST API cannot even read is reported INVALID with its reason, not as an unexpected failure" {
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "key", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "NO_TYPE_$suffix" } } ] }
                    """.trimIndent(),
                )

            val result = applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())["results"].single()

            result["status"].asText() shouldBe "INVALID"
            result["message"].asText() shouldNotContain "Unexpected error"
        }

        "a secret must be declared on a secret field of the auth setting's own type, and never written in clear" {
            val result =
                proposeTool(alice).executeWithJson(
                    """
                    { "operations": [
                        { "ref": "basic", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "B_$suffix", "authType": "BASIC_AUTH", "username": "u" },
                          "secrets": [ { "path": "/apiKey", "label": "Password" } ] },
                        { "ref": "clear", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "K_$suffix", "authType": "API_KEY", "apiKey": "sk-live-$suffix" } },
                        { "ref": "jira", "kind": "INTEGRATION", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "J_$suffix", "integrationType": "JIRA", "parameters": { "token": "abc-$suffix" } } } ] }
                    """.trimIndent(),
                    toolContext(alice),
                )

            result.errorType shouldBe "INVALID_PROPOSAL"
            result.output shouldContain "basic: '/apiKey' is not a secret field of a BASIC_AUTH auth setting"
            result.output shouldContain "clear: '/apiKey' holds a secret"
            result.output shouldContain "jira: '/parameters/token' holds a secret"
        }

        "removing a field of an auth setting clears it, since the REST API reads a missing one as keep" {
            // A namespace-shared auth setting is read and written by id by its super-admins only.
            val root = superAdmin()
            actAs(root)
            val creation =
                propose(
                    root,
                    """
                    { "operations": [
                        { "ref": "oauth", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "OAUTH_$suffix", "authType": "OAUTH_REGISTERED", "clientId": "client",
                                     "authorizationUrl": "https://idp.example.com/authorize", "tokenUrl": "https://idp.example.com/token", "scopes": "read" },
                          "secrets": [ { "path": "/clientSecret", "label": "Client secret" } ] } ] }
                    """.trimIndent(),
                )
            val settingId =
                applied(creation.questionId, view(creation.questionId)["planHash"].asText(), mapOf("oauth./clientSecret" to "s3cret"))["results"]
                    .single()["entityId"]
                    .asText()
            val proposal =
                propose(
                    root,
                    """
                    { "operations": [
                        { "ref": "scopes", "kind": "AUTH_SETTING", "action": "UPDATE", "id": "$settingId",
                          "changes": [ { "path": "/scopes", "remove": true } ] } ] }
                    """.trimIndent(),
                )

            applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())["decision"].asText() shouldBe "APPLIED"

            (authSettingService.findById(UUID.fromString(settingId)) as OAuthRegisteredAuthSetting).scopes.isNullOrBlank() shouldBe true
            view(proposal.questionId)["operations"].single()["status"].asText() shouldBe "APPLIED"
        }

        "an agent created but not deployed makes the plan partial, not failed" {
            every { configWriter.setDeployed(any(), any(), true) } throws AccessDeniedException("no deployment for you")
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "a", "kind": "AGENT", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "lonely-$suffix", "enabled": true }, "deployTo": "NAMESPACE" } ] }
                    """.trimIndent(),
                )

            val report = applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())

            report["decision"].asText() shouldBe "PARTIAL"
            report["results"].single()["status"].asText() shouldBe "DENIED"
        }

        "an expired plan can still be rejected, which answers the agent" {
            val proposal = propose(alice, promptPlan("stale"))
            clock.now = clock.now.plus(ConfigAgentLimits.PROPOSAL_TTL).plusSeconds(1)
            view(proposal.questionId)["canReject"].asBoolean() shouldBe true

            mockMvc
                .perform(
                    post("/api/cases/${case.id}/config-changes/${proposal.questionId}/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                ).andExpect(status().is2xxSuccessful)

            view(proposal.questionId)["state"].asText() shouldBe "ANSWERED"
        }

        "creating an agent needs an explicit enabled flag" {
            val result =
                proposeTool(alice).executeWithJson(
                    """
                    { "operations": [
                        { "ref": "a", "kind": "AGENT", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "draft-$suffix" }, "deployTo": "NAMESPACE" } ] }
                    """.trimIndent(),
                    toolContext(alice),
                )

            result.errorType shouldBe "INVALID_PROPOSAL"
            result.output shouldContain "a: creating an agent needs 'value.enabled'"
        }

        "a newer plan supersedes an older one for good, even once answered" {
            val older = propose(alice, promptPlan("first"))
            val newer = propose(alice, promptPlan("second"))
            view(older.questionId)["state"].asText() shouldBe "SUPERSEDED"

            applied(newer.questionId, view(newer.questionId)["planHash"].asText())["decision"].asText() shouldBe "APPLIED"

            view(older.questionId)["state"].asText() shouldBe "SUPERSEDED"
            apply(older.questionId, view(older.questionId)["planHash"].asText()).andExpect(status().isConflict)
        }

        "a proposal with a mislabelled scope, a secret in a plain field, a path through a list or an unknown reference is refused" {
            val result =
                proposeTool(alice).executeWithJson(
                    """
                    { "operations": [
                        { "ref": "a", "kind": "AGENT", "action": "CREATE", "scope": { "namespaceId": "current", "userId": "me" },
                          "value": { "name": "mine" }, "deployTo": "NONE",
                          "secrets": [ { "path": "/instructions", "label": "API key" } ] },
                        { "ref": "p", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "${UUID.randomUUID()}" },
                          "value": { "name": "elsewhere", "content": ["x"] } },
                        { "ref": "d", "kind": "AGENT", "action": "UPDATE", "id": "${devAgent.id}",
                          "changes": [ { "path": "/subAgents/0", "value": "x" }, { "path": "/instructions", "value": { "${'$'}op": "nope" } } ] } ] }
                    """.trimIndent(),
                    ToolContext(namespaceId = namespace.id, userId = alice.id, userExternalId = null, caseEvents = emptyList()),
                )

            result.errorType shouldBe "INVALID_PROPOSAL"
            result.output shouldContain "agents have no personal scope"
            result.output shouldContain "cannot hold a secret"
            result.output shouldContain "must be this namespace"
            result.output shouldContain "goes through a list"
            result.output shouldContain "unknown reference"
        }

        "a value the REST API would refuse with 400 is reported INVALID, not written" {
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "dev", "kind": "AGENT", "action": "UPDATE", "id": "${devAgent.id}",
                          "changes": [ { "path": "/name", "value": "  " } ] } ] }
                    """.trimIndent(),
                )

            val report = applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())

            report["results"].single()["status"].asText() shouldBe "INVALID"
            agentConfigService.findById(devAgent.id)!!.name shouldBe devAgent.name
        }

        "a member's plan is refused by the REST rules at apply time, and nothing is written" {
            actAs(carol)
            val proposal =
                propose(
                    carol,
                    """
                    { "operations": [
                        { "ref": "shared", "kind": "INTEGRATION", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "SHARED_$suffix", "integrationType": "JIRA" } } ] }
                    """.trimIndent(),
                )

            val report = applied(proposal.questionId, view(proposal.questionId)["planHash"].asText())

            report["decision"].asText() shouldBe "FAILED"
            report["results"].single()["status"].asText() shouldBe "DENIED"
            integrationConfigService.findByNamespaceShared(namespace.id).map { it.name }.contains("SHARED_$suffix") shouldBe false
        }

        "a secret is typed at apply time, never proposed" {
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "key", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current", "userId": "me" },
                          "value": { "name": "ACME_KEY_$suffix", "authType": "API_KEY" },
                          "secrets": [ { "path": "/apiKey", "label": "Acme API key" } ] } ] }
                    """.trimIndent(),
                )
            val shown = view(proposal.questionId)
            shown["operations"].single()["secretInputs"].single()["key"].asText() shouldBe "key./apiKey"

            apply(proposal.questionId, shown["planHash"].asText()).andExpect(status().isUnprocessableEntity)
            val report = applied(proposal.questionId, shown["planHash"].asText(), mapOf("key./apiKey" to "s3cr3t-$suffix"))

            report["decision"].asText() shouldBe "APPLIED"
            val stored = authSettingService.findByUserId(alice.id).single { it.name == "ACME_KEY_$suffix" } as ApiKeyAuthSetting
            stored.apiKey shouldBe "s3cr3t-$suffix"
            stored.namespaceId shouldBe namespace.id
        }

        "an OAuth auth setting is created with its client secret typed in the card" {
            val proposal =
                propose(
                    alice,
                    """
                    { "operations": [
                        { "ref": "oauth", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "ACME_OAUTH_$suffix", "authType": "OAUTH_REGISTERED", "clientId": "acme-client",
                                     "authorizationUrl": "https://acme.example.com/authorize", "tokenUrl": "https://acme.example.com/token",
                                     "scopes": "read write" },
                          "secrets": [ { "path": "/clientSecret", "label": "Acme client secret" } ] } ] }
                    """.trimIndent(),
                )
            val shown = view(proposal.questionId)

            val report = applied(proposal.questionId, shown["planHash"].asText(), mapOf("oauth./clientSecret" to "client-s3cr3t-$suffix"))

            report["decision"].asText() shouldBe "APPLIED"
            val stored = authSettingService.findByNamespaceId(namespace.id).single { it.name == "ACME_OAUTH_$suffix" } as OAuthRegisteredAuthSetting
            stored.clientId shouldBe "acme-client"
            stored.clientSecret shouldBe "client-s3cr3t-$suffix"
            stored.tokenUrl shouldBe "https://acme.example.com/token"
            stored.userId shouldBe null
        }

        "a proposal above the operation limit is refused" {
            val operations =
                (1..ConfigAgentLimits.MAX_OPERATIONS + 1).joinToString(",") { index ->
                    """{ "ref": "p$index", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "current" },
                       "value": { "name": "limit-$index-$suffix", "content": ["x"] } }"""
                }
            val result =
                proposeTool(alice).executeWithJson(
                    """{ "operations": [ $operations ] }""",
                    ToolContext(namespaceId = namespace.id, userId = alice.id, userExternalId = null, caseEvents = emptyList()),
                )

            result.errorType shouldBe "INVALID_PROPOSAL"
            result.output shouldContain "at most ${ConfigAgentLimits.MAX_OPERATIONS} operations"
        }

        "an expired plan can no longer be applied" {
            val proposal = propose(alice, promptPlan("late"))
            val planHash = view(proposal.questionId)["planHash"].asText()

            clock.now = clock.now.plus(ConfigAgentLimits.PROPOSAL_TTL).plusSeconds(1)

            view(proposal.questionId)["state"].asText() shouldBe "EXPIRED"
            apply(proposal.questionId, planHash).andExpect(status().isConflict)
        }

        "a second click while the plan is being applied is refused, apply or reject" {
            val proposal = propose(alice, promptPlan("busy"))
            val planHash = view(proposal.questionId)["planHash"].asText()
            val answering = CountDownLatch(1)
            val release = CountDownLatch(1)
            every { caseService.addMessage(any(), any(), any(), any(), any()) } answers {
                answering.countDown()
                release.await(10, TimeUnit.SECONDS)
            }

            var firstStatus = 0
            val first = thread { firstStatus = apply(proposal.questionId, planHash).andReturn().response.status }
            answering.await(10, TimeUnit.SECONDS) shouldBe true
            apply(proposal.questionId, planHash).andExpect(status().isConflict)
            mockMvc
                .perform(
                    post("/api/cases/${case.id}/config-changes/${proposal.questionId}/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                ).andExpect(status().isConflict)
            release.countDown()
            first.join()
            firstStatus shouldBe 200
        }

        "an invalid proposal is returned to the LLM instead of reaching the user" {
            val result =
                proposeTool(alice).executeWithJson(
                    """
                    { "operations": [
                        { "ref": "key", "kind": "AUTH_SETTING", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "K", "authType": "API_KEY", "apiKey": "****" } },
                        { "ref": "p", "kind": "PROMPT", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "P", "content": ["x"], "agentConfigId": { "${'$'}op": "missing" } } },
                        { "ref": "a", "kind": "AGENT", "action": "CREATE", "scope": { "namespaceId": "current" }, "value": { "name": "A" } } ] }
                    """.trimIndent(),
                    ToolContext(namespaceId = namespace.id, userId = alice.id, userExternalId = null, caseEvents = emptyList()),
                )

            result.success shouldBe false
            result.errorType shouldBe "INVALID_PROPOSAL"
            result.output shouldContain "masked secret"
            result.output shouldContain "unknown reference"
            result.output shouldContain "deployTo"
        }
    }

    /** A clock a test can move; primary so it replaces the application's UTC clock in this context. */
    class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    @TestConfiguration
    class MovableClockConfiguration {
        @Bean
        @Primary
        fun movableClock(): MovableClock = MovableClock(Instant.now())
    }
}
