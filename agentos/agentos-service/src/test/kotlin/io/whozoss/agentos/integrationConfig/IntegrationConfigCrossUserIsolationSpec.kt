package io.whozoss.agentos.integrationConfig

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Cross-user isolation matrix for the unified [IntegrationConfigController] (NFR-SEC-1).
 *
 * Mirrors [io.whozoss.agentos.aiProvider.AiProviderCrossUserIsolationSpec] —
 * `(verb × scope) ∈ {GET, PUT, DELETE} × {user-global, user × namespace}` always
 * returns 404 (Decision 11/20). The two LIST scenarios verify alice never sees bob's
 * rows and that `?userId=<bob>` returns 400 post-fusion (Decision 15 — only `me`
 * sentinel exposed).
 *
 * Migrated from `UserIntegrationConfigCrossUserIsolationSpec` (Decision 19 + Task 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class IntegrationConfigCrossUserIsolationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var integrationConfigService: IntegrationConfigService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService
    @MockkBean(relaxed = true) lateinit var namespaceService: NamespaceService

    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    private val sharedNamespaceId = UUID.randomUUID()
    private val sharedNamespace = Namespace(
        metadata = EntityMetadata(id = sharedNamespaceId),
        externalId = "ns-${sharedNamespaceId}",
        name = "shared",
    )

    init {
        beforeEach {
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every { namespaceService.findById(sharedNamespaceId) } returns sharedNamespace
            every { permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(bobId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ) } returns true
        }

        fun createBobRow(namespaceId: UUID?, name: String): IntegrationConfig =
            integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    userId = bobId,
                    name = name,
                    integrationType = "JIRA",
                ),
            )

        // ---------------------------------------------------------------------
        // GET — both modes
        // ---------------------------------------------------------------------
        "GET alice → bob.user-global returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        "GET alice → bob.user-namespace returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // PUT — both modes
        // ---------------------------------------------------------------------
        "PUT alice → bob.user-global returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/integration-configs/${bobCfg.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "ATTACK", "integrationType": "JIRA" }"""),
            ).andExpect(status().isNotFound)
        }

        "PUT alice → bob.user-namespace returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/integration-configs/${bobCfg.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "ATTACK", "integrationType": "JIRA" }"""),
            ).andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // DELETE — both modes
        // ---------------------------------------------------------------------
        "DELETE alice → bob.user-global returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE alice → bob.user-namespace returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // LIST
        // ---------------------------------------------------------------------
        "LIST as alice never includes bob's rows (either mode)" {
            createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/integration-configs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.userId != '$aliceId')]").isEmpty)
        }

        "LIST as alice with ?userId=<bob.id> returns 400 (only 'me' sentinel exposed)" {
            createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/integration-configs?userId=$bobId"))
                .andExpect(status().isBadRequest)
        }
    }
}
