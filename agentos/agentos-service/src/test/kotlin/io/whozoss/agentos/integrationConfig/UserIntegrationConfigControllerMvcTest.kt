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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [UserIntegrationConfigController] — verifies Bean Validation activation,
 * JSON deserialisation, and that the @HideOnAccessDenied → 404 translation works end-to-end
 * inside a real Spring Boot context.
 *
 * Uses `webEnvironment = MOCK` with `@ActiveProfiles("test")` (in-memory persistence). UserService
 * and PermissionService are mocked so we can simulate identity and namespace authorization
 * without provisioning real users/relations. The controller writes to the real in-memory
 * IntegrationConfigService so duplicate-detection (409) surfaces naturally.
 *
 * Cross-user isolation (alice attempting to access bob's resources) is in
 * [UserIntegrationConfigCrossUserIsolationSpec] — kept separate to keep this spec focused on
 * the HTTP-shape contract.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserIntegrationConfigControllerMvcTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var integrationConfigService: IntegrationConfigService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val aliceId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    private val namespaceId = UUID.randomUUID()

    init {

        beforeEach {
            every { userService.getCurrentUser() } returns alice
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true
        }

        // ---------------------------------------------------------------------
        // 1. POST without body → 400 (HttpMessageNotReadable)
        // ---------------------------------------------------------------------
        "POST /api/user-integration-configs without body returns 400" {
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
        }

        // ---------------------------------------------------------------------
        // 2. POST with blank name → 400 Bean Validation
        // ---------------------------------------------------------------------
        "POST with blank name returns 400" {
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "", "integrationType": "JIRA" }"""),
            ).andExpect(status().isBadRequest)
        }

        // ---------------------------------------------------------------------
        // 3. POST with blank integrationType → 400 Bean Validation
        // ---------------------------------------------------------------------
        "POST with blank integrationType returns 400" {
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "JIRA_PROD", "integrationType": "" }"""),
            ).andExpect(status().isBadRequest)
        }

        // ---------------------------------------------------------------------
        // 4. POST minimal valid (user-global) → 201
        // ---------------------------------------------------------------------
        "POST user-global (no namespaceId) returns 201" {
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "GLOBAL_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
        }

        // ---------------------------------------------------------------------
        // 5. POST with permitted namespaceId → 201
        // ---------------------------------------------------------------------
        "POST user-namespace with READ permission returns 201" {
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "NS_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
        }

        // ---------------------------------------------------------------------
        // 6. POST with non-permitted namespaceId → 403
        // ---------------------------------------------------------------------
        "POST user-namespace without READ permission returns 403" {
            val foreignNs = UUID.randomUUID()
            // permissionService default returns false — no grant set up for foreignNs.
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$foreignNs", "name": "FOREIGN_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isForbidden)
        }

        // ---------------------------------------------------------------------
        // 7. POST duplicate (namespaceId, userId, name) → 409 from service-layer uniqueness
        // ---------------------------------------------------------------------
        "POST duplicate triple returns 409" {
            val name = "DUP_${UUID.randomUUID()}"
            // First create succeeds.
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$name", "integrationType": "JIRA" }"""),
            ).andExpect(status().isCreated)

            // Second create with same triple → 409 Conflict.
            mockMvc.perform(
                post("/api/user-integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$name", "integrationType": "JIRA" }"""),
            ).andExpect(status().isConflict)
        }

        // ---------------------------------------------------------------------
        // 8. PUT with body changing userId → 200, but userId stays alice
        // ---------------------------------------------------------------------
        "PUT preserves userId even when body sends another" {
            // Pre-create as alice.
            val created = integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "PUT_${UUID.randomUUID()}",
                    integrationType = "JIRA",
                ),
            )
            val attackerUserId = UUID.randomUUID()

            mockMvc.perform(
                put("/api/user-integration-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{ "id": "${created.id}", "userId": "$attackerUserId", "name": "${created.name}", "integrationType": "JIRA", "description": "renamed" }""",
                    ),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.description").value("renamed"))
        }

        // ---------------------------------------------------------------------
        // 9. DELETE on own row → 204
        // ---------------------------------------------------------------------
        "DELETE on own row returns 204" {
            val created = integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "DEL_${UUID.randomUUID()}",
                    integrationType = "JIRA",
                ),
            )
            mockMvc.perform(delete("/api/user-integration-configs/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // ---------------------------------------------------------------------
        // 10. GET non-existent id → 404 (existence-hiding via @HideOnAccessDenied)
        // ---------------------------------------------------------------------
        "GET non-existent id returns 404 (not 403)" {
            mockMvc.perform(get("/api/user-integration-configs/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // 11. LIST returns the caller's configs in JSON envelope
        // ---------------------------------------------------------------------
        "LIST returns paginated envelope for caller's configs" {
            val tag = "LIST_${UUID.randomUUID()}"
            integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "$tag-A",
                    integrationType = "JIRA",
                ),
            )

            mockMvc.perform(get("/api/user-integration-configs?size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content").isArray)
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.page").value(0))
        }
    }
}
