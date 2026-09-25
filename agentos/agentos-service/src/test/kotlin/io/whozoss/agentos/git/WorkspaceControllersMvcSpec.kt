package io.whozoss.agentos.git

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Real namespace Git MVC and method-security wiring.
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
    @Autowired lateinit var namespaces: NamespaceService

    @MockkBean(relaxed = true) lateinit var users: UserService
    @MockkBean(relaxed = true) lateinit var permissions: PermissionService
    @MockkBean(relaxed = true) lateinit var associations: GitRepositoryAssociationService
    @MockkBean(relaxed = true) lateinit var integrationConfigs: IntegrationConfigService
    @MockkBean(relaxed = true) lateinit var checkoutProvisioner: RepositoryCheckoutProvisioner
    @MockkBean lateinit var gitAvailability: GitAvailability

    private val user = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = "workspace-reader@example.com",
        email = "workspace-reader@example.com",
        isAdmin = false,
    )

    private fun allow(type: EntityType, id: UUID, action: Action) {
        every { permissions.hasPermission(user.id.toString(), type, id.toString(), action) } returns true
    }

    init {
        beforeTest {
            every { users.getCurrentUser() } returns user
            every { permissions.hasPermission(any(), any(), any(), any()) } returns false
            every { gitAvailability.isAvailable() } returns true
        }

        "namespace Git settings do not exist while the GIT plugin is not loaded" {
            val namespaceId = UUID.randomUUID()
            allow(EntityType.NAMESPACE, namespaceId, Action.READ)
            allow(EntityType.NAMESPACE, namespaceId, Action.WRITE)
            every { gitAvailability.isAvailable() } returns false
            val body = """{"repositoryUrl":"https://example.com/repository.git","serviceAuthSettingId":"${UUID.randomUUID()}"}"""

            mockMvc.perform(get("/api/namespaces/$namespaceId/git")).andExpect(status().isNotFound)
            mockMvc.perform(put("/api/namespaces/$namespaceId/git").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound)
            mockMvc.perform(delete("/api/namespaces/$namespaceId/git")).andExpect(status().isNotFound)
            verify(exactly = 0) { associations.findSettings(namespaceId) }
            verify(exactly = 0) { integrationConfigs.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) }
        }

        "a caller without namespace READ cannot inspect Git settings" {
            val namespaceId = UUID.randomUUID()
            listOf("git").forEach { suffix ->
                mockMvc.perform(get("/api/namespaces/$namespaceId/$suffix"))
                    .andExpect(status().isForbidden)
            }
            verify(exactly = 0) { associations.findSettings(namespaceId) }
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

        "even a super-admin cannot associate a missing or removed namespace" {
            every { users.getCurrentUser() } returns user.copy(isAdmin = true)
            val removed = namespaces.create(Namespace(name = "Removed Git namespace"))
            namespaces.delete(removed.id)
            for (namespaceId in listOf(UUID.randomUUID(), removed.id)) {
                allow(EntityType.NAMESPACE, namespaceId, Action.WRITE)
                val body = """{"repositoryUrl":"https://example.com/repository.git","serviceAuthSettingId":"${UUID.randomUUID()}"}"""
                mockMvc.perform(put("/api/namespaces/$namespaceId/git").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound)
                verify(exactly = 0) { integrationConfigs.create(match { it.namespaceId == namespaceId }) }
                verify(exactly = 0) { integrationConfigs.update(match { it.namespaceId == namespaceId }) }
                verify(exactly = 0) { checkoutProvisioner.requestPreparation(match { it.namespaceId == namespaceId }) }
            }
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
