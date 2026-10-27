package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
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
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

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
@Import(EmbeddedNeo4jTestConfiguration::class)
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

    private lateinit var alice: User // ADMIN of the namespace, the user the agent runs for
    private lateinit var bob: User // may write in the case, nothing else
    private lateinit var carol: User // MEMBER of the namespace
    private lateinit var namespace: Namespace
    private lateinit var case: Case
    private lateinit var devAgent: AgentConfig
    private lateinit var suffix: String

    private fun user(): User {
        val id = UUID.randomUUID()
        return User(metadata = EntityMetadata(id = id), externalId = "cfg-$id@example.com", email = "cfg-$id@example.com", isAdmin = false)
    }

    private fun actAs(user: User) {
        every { userService.getCurrentUser() } returns user
    }

    private fun proposeTool(caller: User): StandardTool<*> =
        configToolGrantService
            .grantTools(
                integrations = mapOf(ConfigAgentIntegration.TYPE to null),
                agentName = "config",
                namespaceId = namespace.id,
                userId = caller.id,
                caseId = case.id,
            ).single { it.name == ConfigAgentIntegration.PROPOSE_TOOL_NAME }

    /** Runs the propose tool as [caller] and records what an agent run would have persisted. */
    private suspend fun propose(
        caller: User,
        input: String,
    ): AgentInterrupt.AwaitAnswer {
        val interrupt =
            shouldThrow<AgentInterrupt.AwaitAnswer> {
                proposeTool(caller).executeWithJson(input, ToolContext(namespaceId = namespace.id, userId = caller.id, userExternalId = null, caseEvents = emptyList()))
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
                          "value": { "name": "support-$suffix", "instructions": "Help the user" }, "deployTo": "NAMESPACE" },
                        { "ref": "acme", "kind": "INTEGRATION", "action": "CREATE", "scope": { "namespaceId": "current", "userId": null },
                          "value": { "name": "ACME_$suffix", "integrationType": "JIRA", "description": "Acme" } }
                      ] }
                    """.trimIndent()

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
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = namespace.id, name = "dev-$suffix", enabled = true),
                )
            actAs(alice)
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

        "a newer plan supersedes an older one for good, even once answered" {
            val older = propose(alice, promptPlan("first"))
            val newer = propose(alice, promptPlan("second"))
            view(older.questionId)["state"].asText() shouldBe "SUPERSEDED"

            applied(newer.questionId, view(newer.questionId)["planHash"].asText())["decision"].asText() shouldBe "APPLIED"

            view(older.questionId)["state"].asText() shouldBe "SUPERSEDED"
            apply(older.questionId, view(older.questionId)["planHash"].asText()).andExpect(status().isConflict)
        }

        "a proposal that would mislabel its scope or hide a secret in a plain field is refused" {
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
}
