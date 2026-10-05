package io.whozoss.agentos.usage

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = ["agentos.usage.enabled=true"])
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

        "a child editor can read the blocking parent pause but needs parent write permission to continue" {
            val child = createChild(100.0, PermissionRelation.ADMIN)
            val usage = UsageAccumulator()
            val registration = costs.register(child.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                mvc
                    .perform(get("/api/cases/${child.id}/run-cost"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.paused").value(true))
                    .andExpect(jsonPath("$.runCostThreshold").value(100.0))
                    .andExpect(jsonPath("$.pausedCases.length()").value(1))
                    .andExpect(jsonPath("$.pausedCases[0].caseId").value(case.id.toString()))
                    .andExpect(jsonPath("$.pausedCases[0].cost").value(12.0))
                    .andExpect(jsonPath("$.pausedCases[0].threshold").value(10.0))
                    .andExpect(jsonPath("$.pausedCases[0].ancestor").value(true))
                    .andExpect(jsonPath("$.pausedCases[0].canContinue").value(false))
                confirm(case.id).andExpect(status().isForbidden)
                pending.isDone shouldBe false
                cases.findById(case.id)!!.runCostThreshold shouldBe 10.0
                cases.findById(child.id)!!.runCostThreshold shouldBe 100.0
            } finally {
                costs.stop(case.id)
                registration.finish {}
            }
        }

        "a child reader with parent write permission can release the parent gate without changing the child threshold" {
            permissions.grantPermission(reader.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            val child = createChild(100.0, PermissionRelation.MEMBER)
            val usage = UsageAccumulator()
            val registration = costs.register(child.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                mvc
                    .perform(get("/api/cases/${child.id}/run-cost"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.paused").value(true))
                    .andExpect(jsonPath("$.pausedCases[0].caseId").value(case.id.toString()))
                    .andExpect(jsonPath("$.pausedCases[0].ancestor").value(true))
                    .andExpect(jsonPath("$.pausedCases[0].canContinue").value(true))
                confirm(child.id, 100.0).andExpect(status().isForbidden)
                confirm(case.id)
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.runCostThreshold").value(20.0))
                pending.isDone shouldBe true
                pending.isCompletedExceptionally shouldBe false
                cases.findById(case.id)!!.runCostThreshold shouldBe 20.0
                cases.findById(child.id)!!.runCostThreshold shouldBe 100.0
                mvc
                    .perform(get("/api/cases/${child.id}/run-cost"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.paused").value(false))
                    .andExpect(jsonPath("$.pausedCases").isEmpty)
            } finally {
                costs.stop(case.id)
                registration.finish {}
            }
        }

        "a child editor without parent read permission sees a pause without parent details" {
            val child = createChild(100.0, PermissionRelation.ADMIN)
            permissions.revokePermission(reader.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.MEMBER)
            val usage = UsageAccumulator()
            val registration = costs.register(child.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                val response =
                    mvc
                        .perform(get("/api/cases/${child.id}/run-cost"))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.paused").value(true))
                        .andExpect(jsonPath("$.caseId").value(child.id.toString()))
                        .andExpect(jsonPath("$.runCostThreshold").value(100.0))
                        .andExpect(jsonPath("$.pausedCases").isEmpty)
                        .andReturn()
                response.response.contentAsString shouldNotContain case.id.toString()
                mvc.perform(get("/api/cases/${case.id}/run-cost")).andExpect(status().isForbidden)
                confirm(case.id).andExpect(status().isForbidden)
                pending.isDone shouldBe false
                cases.findById(case.id)!!.runCostThreshold shouldBe 10.0
            } finally {
                costs.stop(case.id)
                registration.finish {}
            }
        }

        "the continuation response hides a remaining child pause that the parent editor cannot read" {
            permissions.grantPermission(reader.id.toString(), EntityType.CASE, case.id.toString(), PermissionRelation.ADMIN)
            val child = createChild(5.0)
            val usage = UsageAccumulator()
            val registration = costs.register(child.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                costs.state(case.id).pausedCases.map { it.caseId }.toSet() shouldBe setOf(case.id, child.id)
                val response =
                    confirm(case.id)
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.runCostThreshold").value(20.0))
                        .andExpect(jsonPath("$.pausedCases").isEmpty)
                        .andReturn()
                response.response.contentAsString shouldNotContain child.id.toString()
                mvc.perform(get("/api/cases/${child.id}/run-cost")).andExpect(status().isForbidden)
                costs.state(child.id).paused shouldBe true
                pending.isDone shouldBe false
                cases.findById(child.id)!!.runCostThreshold shouldBe 5.0
            } finally {
                costs.stop(case.id)
                registration.finish {}
            }
        }

        "a super admin can see and continue an ancestor pause without direct case permissions" {
            val child = createChild(100.0)
            val admin = users.save(User(externalId = "cost-admin-${UUID.randomUUID()}", email = "admin@example.com", isAdmin = true))
            every { identity.getCurrentUser() } returns admin
            every { identity.findById(admin.id) } returns admin
            val usage = UsageAccumulator()
            val registration = costs.register(child.id, usage)
            try {
                usage.record(LlmUsage(totalTokens = 10, estimatedCostUsd = 12.0))
                val pending = usage.beforeCall()
                mvc
                    .perform(get("/api/cases/${child.id}/run-cost"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.paused").value(true))
                    .andExpect(jsonPath("$.pausedCases.length()").value(1))
                    .andExpect(jsonPath("$.pausedCases[0].caseId").value(case.id.toString()))
                    .andExpect(jsonPath("$.pausedCases[0].ancestor").value(true))
                    .andExpect(jsonPath("$.pausedCases[0].canContinue").value(true))
                confirm(case.id).andExpect(status().isOk)
                pending.isDone shouldBe true
                pending.isCompletedExceptionally shouldBe false
            } finally {
                costs.stop(case.id)
                registration.finish {}
            }
        }
    }

    private fun createChild(threshold: Double, relation: PermissionRelation? = null): Case {
        val child = cases.save(Case(namespaceId = case.namespaceId, parentCaseId = case.id, runCostThreshold = threshold))
        cases.linkParentToChild(case.id, child.id)
        if (relation != null) {
            permissions.grantPermission(reader.id.toString(), EntityType.CASE, child.id.toString(), relation)
        }
        return child
    }

    private fun confirm(caseId: UUID, threshold: Double = 10.0) =
        mvc.perform(
            post("/api/cases/$caseId/run-cost/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedThreshold":$threshold}"""),
        )
}
