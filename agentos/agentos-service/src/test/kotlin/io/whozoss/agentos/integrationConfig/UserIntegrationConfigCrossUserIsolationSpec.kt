package io.whozoss.agentos.integrationConfig

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
 * Cross-user isolation matrix for [UserIntegrationConfigController] (NFR-SEC-1).
 *
 * For each (verb × mode) pair where `verb ∈ {GET, PUT, DELETE}` and
 * `mode ∈ {user-global, user × namespace}`, alice attempts to access bob's resource.
 * **Every cell must return 404 Not Found** — never 403 Forbidden — because 403 leaks the
 * existence of the row to a stranger and lets an attacker enumerate ids.
 *
 * Plus two LIST scenarios that verify alice never sees bob's rows in either listing mode,
 * even when she explicitly supplies `?userId=bob.id` (server-side `userId` is forced to
 * `auth.name`, mass-assignment guard FR20).
 *
 * The Guard's translation to AccessDeniedException + the controller's
 * `@HideOnAccessDenied` annotation is what produces the 404 — see
 * [io.whozoss.agentos.security.declarative.AccessDeniedExceptionHandler].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserIntegrationConfigCrossUserIsolationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var integrationConfigService: IntegrationConfigService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    private val bob = User(
        metadata = EntityMetadata(id = bobId),
        externalId = "bob@example.com",
        email = "bob@example.com",
        isAdmin = false,
    )

    private val sharedNamespaceId = UUID.randomUUID()

    init {

        beforeEach {
            // Both alice and bob hold READ on the shared namespace — so the namespace-mode
            // creation succeeds for both. The cross-user isolation under test is purely
            // ownership-based, NOT namespace-derived.
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every { permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(bobId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ) } returns true
        }

        // Helper: pre-create the four bob rows we'll attack as alice. We use the service
        // directly so we don't need to switch the mocked auth twice in the same test.
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
        "GET alice→bob.user-global returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        "GET alice→bob.user-namespace returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // PUT — both modes
        // ---------------------------------------------------------------------
        "PUT alice→bob.user-global returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/user-integration-configs/${bobCfg.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "ATTACK", "integrationType": "JIRA" }"""),
            ).andExpect(status().isNotFound)
        }

        "PUT alice→bob.user-namespace returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/user-integration-configs/${bobCfg.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "ATTACK", "integrationType": "JIRA" }"""),
            ).andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // DELETE — both modes
        // ---------------------------------------------------------------------
        "DELETE alice→bob.user-global returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/user-integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE alice→bob.user-namespace returns 404 (not 403)" {
            val bobCfg = createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/user-integration-configs/${bobCfg.id}"))
                .andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // LIST — alice never sees bob's rows in either mode
        // ---------------------------------------------------------------------
        "LIST as alice never includes bob's rows (either mode)" {
            createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            createBobRow(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-integration-configs?size=100"))
                .andExpect(status().isOk)
                // Every returned row must have userId == alice.
                .andExpect(jsonPath("$.content[?(@.userId != '$aliceId')]").isEmpty)
        }

        // ---------------------------------------------------------------------
        // LIST mass-assignment — ?userId=bob is ignored, server forces auth.name
        // ---------------------------------------------------------------------
        "LIST as alice with ?userId=bob still filters on alice (server-side userId guard)" {
            createBobRow(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-integration-configs?userId=$bobId&size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[?(@.userId != '$aliceId')]").isEmpty)
        }
    }
}
