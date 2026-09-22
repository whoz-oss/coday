package io.whozoss.agentos.usage

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.chat.UsageAccumulator
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.usage.LlmUsage
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Real HTTP authorization, cost service and Neo4j; only request identity is substituted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class RunCostControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var costs: RunCostService

    @Autowired lateinit var cases: CaseRepository

    @Autowired lateinit var namespaces: NamespaceService

    @Autowired lateinit var records: UsageRecordService

    @Autowired lateinit var permissions: PermissionService

    @Autowired lateinit var users: UserRepository

    @MockkBean(relaxed = true)
    lateinit var identity: UserService

    private lateinit var case: Case
    private lateinit var reader: User

    init {
        beforeEach {
            reader = users.save(User(externalId = "cost-${UUID.randomUUID()}", email = "cost@example.com", isAdmin = false))
            every { identity.getCurrentUser() } returns reader
            every { identity.findById(reader.id) } returns reader
            val namespace = namespaces.create(Namespace(externalId = "cost-${UUID.randomUUID()}", name = "Cost test"))
            case = cases.save(Case(namespaceId = namespace.id, runCostThreshold = 10.0))
            permissions.grantPermission(reader.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.MEMBER)
        }

        "a reader can inspect a paused run but cannot double or stop it" {
            val usage = UsageAccumulator()
            val registration = costs.register(case.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                mvc
                    .perform(get("/api/cases/${case.id}/run-cost"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.paused").value(true))
                mvc
                    .perform(
                        post("/api/cases/${case.id}/run-cost/continue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"expectedThreshold":10} """),
                    ).andExpect(status().isForbidden)
                mvc.perform(post("/api/cases/${case.id}/run-cost/stop")).andExpect(status().isForbidden)
                pending.isDone shouldBe false
                cases.findById(case.id)!!.runCostThreshold shouldBe 10.0
            } finally {
                costs.stop(case.id)
                registration.finish {}
            }
        }

        "an editor confirms once and the doubled threshold survives a fresh read" {
            permissions.grantPermission(reader.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            val usage = UsageAccumulator()
            val registration = costs.register(case.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()

                fun confirm() =
                    mvc.perform(
                        post("/api/cases/${case.id}/run-cost/continue")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"expectedThreshold":10}"""),
                    )
                confirm().andExpect(status().isOk).andExpect(jsonPath("$.runCostThreshold").value(20.0))
                pending.isDone shouldBe true
                confirm().andExpect(status().isConflict)
                cases.findById(case.id)!!.runCostThreshold shouldBe 20.0
            } finally {
                registration.finish {}
            }
        }

        "persisted priced and unknown usage restores the guard after live accounting closes" {
            val usage = UsageAccumulator()
            val registration = costs.register(case.id, usage)
            usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
            usage.record(LlmUsage(totalTokens = 20, estimatedCostUsd = null))
            registration.finish {
                usage.recordGroups().forEach { group ->
                    records.create(UsageRecord.fromLlmUsage(group, case.namespaceId, case.id, "test", UsageOutcome.COMPLETED))
                }
            }
            mvc
                .perform(get("/api/cases/${case.id}/run-cost"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.cost").value(12.0))
                .andExpect(jsonPath("$.unknownCostCount").value(1))
                .andExpect(jsonPath("$.active").value(false))
            val next = UsageAccumulator()
            val nextRegistration = costs.register(case.id, next)
            try {
                next.beforeCall().isDone shouldBe false
            } finally {
                costs.stop(case.id)
                nextRegistration.finish {}
            }
        }
    }
}
