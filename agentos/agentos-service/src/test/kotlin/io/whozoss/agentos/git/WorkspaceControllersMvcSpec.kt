package io.whozoss.agentos.git

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseEvent.ParticipatingAgent
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseCommandJournal
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path
import java.util.UUID

/**
 * Real MVC and method-security wiring, with the real root resolver and workspace projection.
 * Permission answers and persistence are controlled fixtures: this verifies routing, filtering
 * and JSON contracts, not the Neo4j implementation of permission inheritance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class WorkspaceControllersMvcSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var users: UserService
    @MockkBean(relaxed = true) lateinit var permissions: PermissionService
    @MockkBean(relaxed = true) lateinit var cases: CaseRepository
    @MockkBean(relaxed = true) lateinit var caseService: CaseService
    @MockkBean(relaxed = true) lateinit var bindings: CaseResourceBindingService
    @MockkBean(relaxed = true) lateinit var storage: ExchangeStorageService
    @MockkBean(relaxed = true) lateinit var lifecycle: GitWorkspaceLifecycleService
    @MockkBean(relaxed = true) lateinit var journal: CaseCommandJournal
    @MockkBean(relaxed = true) lateinit var events: CaseEventRepository
    @MockkBean(relaxed = true) lateinit var diffs: ExchangeGitDiff
    @MockkBean(relaxed = true) lateinit var associations: GitRepositoryAssociationService
    @MockkBean(relaxed = true) lateinit var integrationConfigs: IntegrationConfigService
    @MockkBean(relaxed = true) lateinit var checkoutProvisioner: RepositoryCheckoutProvisioner

    private val user = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = "workspace-reader@example.com",
        email = "workspace-reader@example.com",
        isAdmin = false,
    )

    private fun allow(type: EntityType, id: UUID, action: Action) {
        every { permissions.hasPermission(user.id.toString(), type, id.toString(), action) } returns true
    }

    private fun stubCase(case: Case, binding: CaseResourceBinding? = null) {
        every { cases.findByIds(listOf(case.id), any()) } returns listOf(case)
        every { storage.caseRoot(case.namespaceId, case.id, case.metadata.created) } returns Path.of("/fixture/exchange/${case.id}")
        every { bindings.findByRootCaseId(case.id) } returns binding
    }

    init {
        beforeTest {
            every { users.getCurrentUser() } returns user
            every { permissions.hasPermission(any(), any(), any(), any()) } returns false
        }

        "a non-Git case returns an unequipped JSON workspace" {
            val case = Case(namespaceId = UUID.randomUUID())
            stubCase(case)
            allow(EntityType.CASE, case.id, Action.READ)

            mockMvc.perform(get("/api/cases/${case.id}/workspace"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.equipped").value(false))
                .andExpect(jsonPath("$.recoveryRequired").value(false))
                .andExpect(jsonPath("$.branchName").doesNotExist())
        }

        "a caller without case READ cannot inspect workspace environment or diff" {
            val caseId = UUID.randomUUID()
            listOf("workspace", "exchange/environment", "exchange/diff?path=secret.txt").forEach { suffix ->
                mockMvc.perform(get("/api/cases/$caseId/$suffix"))
                    .andExpect(status().isForbidden)
            }
            verify(exactly = 0) { cases.findByIds(listOf(caseId), any()) }
        }

        "a caller without namespace READ cannot inspect Git settings or workspace inventory" {
            val namespaceId = UUID.randomUUID()
            listOf("git", "workspaces").forEach { suffix ->
                mockMvc.perform(get("/api/namespaces/$namespaceId/$suffix"))
                    .andExpect(status().isForbidden)
            }
            verify(exactly = 0) { associations.findSettings(namespaceId) }
            verify(exactly = 0) { bindings.findByParent(namespaceId) }
        }

        "case READ alone cannot refresh retry or recover a workspace" {
            val caseId = UUID.randomUUID()
            allow(EntityType.CASE, caseId, Action.READ)
            listOf("refresh", "retry", "recover").forEach { action ->
                mockMvc.perform(post("/api/cases/$caseId/workspace/$action"))
                    .andExpect(status().isForbidden)
            }
            verify(exactly = 0) { cases.findByIds(listOf(caseId), any()) }
            verify(exactly = 0) { lifecycle.retry(caseId) }
            verify(exactly = 0) { caseService.recoverWorkspaceCase(caseId) }
        }

        "namespace READ alone cannot associate or remove its repository" {
            val namespaceId = UUID.randomUUID()
            allow(EntityType.NAMESPACE, namespaceId, Action.READ)
            val body = """{"repositoryUrl":"https://example.com/repository.git","serviceAuthSettingId":"${UUID.randomUUID()}"}"""
            mockMvc.perform(put("/api/namespaces/$namespaceId/git").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden)
            mockMvc.perform(delete("/api/namespaces/$namespaceId/git"))
                .andExpect(status().isForbidden)
            verify(exactly = 0) { integrationConfigs.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) }
        }

        "namespace inventory excludes private cases even when their bindings exist" {
            val namespaceId = UUID.randomUUID()
            val visible = Case(namespaceId = namespaceId)
            val hidden = Case(namespaceId = namespaceId)
            val visibleBinding = CaseResourceBinding(rootCaseId = visible.id, namespaceId = namespaceId, integrationConfigId = UUID.randomUUID())
            val hiddenBinding = CaseResourceBinding(rootCaseId = hidden.id, namespaceId = namespaceId, integrationConfigId = UUID.randomUUID())
            stubCase(visible, visibleBinding)
            stubCase(hidden, hiddenBinding)
            every { bindings.findByParent(namespaceId) } returns listOf(visibleBinding, hiddenBinding)
            allow(EntityType.NAMESPACE, namespaceId, Action.READ)
            allow(EntityType.CASE, visible.id, Action.READ)

            mockMvc.perform(get("/api/namespaces/$namespaceId/workspaces"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rootCaseId").value(visible.id.toString()))
                .andExpect(jsonPath("$[0].status").value("REQUESTED"))
            verify(exactly = 0) { cases.findByIds(listOf(hidden.id), any()) }
        }

        "environment aggregates only readable members of the same case family" {
            val namespaceId = UUID.randomUUID()
            val root = Case(namespaceId = namespaceId)
            val visible = Case(namespaceId = namespaceId, parentCaseId = root.id)
            val hidden = Case(namespaceId = namespaceId, parentCaseId = root.id)
            val otherRoot = Case(namespaceId = namespaceId)
            listOf(root, visible, hidden, otherRoot).forEach { stubCase(it) }
            every { cases.findIncludingRemovedByNamespace(namespaceId) } returns listOf(root, visible, hidden, otherRoot)
            allow(EntityType.CASE, root.id, Action.READ)
            allow(EntityType.CASE, visible.id, Action.READ)
            allow(EntityType.CASE, otherRoot.id, Action.READ)
            val participants = listOf(ParticipatingAgent(UUID.randomUUID(), "Analyst"))
            every { events.participatingAgents(listOf(root.id, visible.id)) } returns participants

            mockMvc.perform(get("/api/cases/${visible.id}/exchange/environment"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.equipped").value(false))
                .andExpect(jsonPath("$.agents.length()").value(1))
                .andExpect(jsonPath("$.agents[0].name").value("Analyst"))
            verify(exactly = 1) { events.participatingAgents(listOf(root.id, visible.id)) }
            verify(exactly = 0) { events.participatingAgents(match { hidden.id in it || otherRoot.id in it }) }
        }

        "requesting a diff for a non-Git case returns not found" {
            val case = Case(namespaceId = UUID.randomUUID())
            stubCase(case)
            allow(EntityType.CASE, case.id, Action.READ)
            mockMvc.perform(get("/api/cases/${case.id}/exchange/diff").param("path", "README.md"))
                .andExpect(status().isNotFound)
        }

        "a writer can explicitly acknowledge setup replay and receives a JSON workspace" {
            val case = Case(namespaceId = UUID.randomUUID())
            stubCase(case)
            allow(EntityType.CASE, case.id, Action.WRITE)
            mockMvc.perform(
                post("/api/cases/${case.id}/workspace/retry")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"acknowledgeSetupReplay":true}"""),
            )
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.equipped").value(false))
            verify(exactly = 1) { lifecycle.acknowledgeSetup(case.id) }
            verify(exactly = 0) { lifecycle.retry(case.id) }
        }

        "an unassociated namespace returns its explicit JSON state" {
            val namespaceId = UUID.randomUUID()
            allow(EntityType.NAMESPACE, namespaceId, Action.READ)
            every { associations.findSettings(namespaceId) } returns null
            mockMvc.perform(get("/api/namespaces/$namespaceId/git"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.associated").value(false))
        }
    }
}
