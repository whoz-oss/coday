package io.whozoss.agentos.user

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC integration tests for `DELETE /api/users/{id}/access?namespaceId=...` (namespace-scoped offboarding).
 *
 * Verifies the `@PreAuthorize("hasRole('SUPER_ADMIN')")` guard end-to-end through the
 * dispatcher: a non-admin caller must get 403 without the service being invoked, a
 * super-admin caller must get 204 with the service invoked, and a 404 from the service
 * (unknown user) must surface as 404 at the HTTP layer.
 *
 * [UserOffboardingService] is mocked so this spec exercises only the security/routing
 * layer, not the offboarding logic itself (covered by [UserOffboardingServiceImplUnitSpec]).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserControllerRevokeAllAccessIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @Suppress("unused")
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    @MockkBean(relaxed = true) lateinit var userOffboardingService: UserOffboardingService

    private val superAdminId = UUID.randomUUID()
    private val regularUserId = UUID.randomUUID()

    private val superAdmin = User(
        metadata = EntityMetadata(id = superAdminId),
        externalId = "root@example.com",
        email = "root@example.com",
        isAdmin = true,
    )

    private val regularUser = User(
        metadata = EntityMetadata(id = regularUserId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )

    init {
        "DELETE /api/users/{id}/access?namespaceId=... as super-admin returns 204 and invokes the service" {
            val targetId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns superAdmin

            mockMvc.perform(delete("/api/users/$targetId/access?namespaceId=$namespaceId"))
                .andExpect(status().isNoContent)

            verify(exactly = 1) { userOffboardingService.revokeNamespaceAccess(targetId, namespaceId) }
        }

        "DELETE /api/users/{id}/access?namespaceId=... as a non-admin (even on self) returns 403" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns regularUser

            mockMvc.perform(delete("/api/users/$regularUserId/access?namespaceId=$namespaceId"))
                .andExpect(status().isForbidden)

            verify(exactly = 0) { userOffboardingService.revokeNamespaceAccess(any(), any()) }
        }

        "DELETE /api/users/{id}/access?namespaceId=... surfaces a 404 from the service when the user does not exist" {
            val targetId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns superAdmin
            every { userOffboardingService.revokeNamespaceAccess(targetId, namespaceId) } throws ResourceNotFoundException("Entity $targetId not found")

            mockMvc.perform(delete("/api/users/$targetId/access?namespaceId=$namespaceId"))
                .andExpect(status().isNotFound)
        }

        "DELETE /api/users/{id}/access without namespaceId returns 400 (missing required query param)" {
            val targetId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns superAdmin

            mockMvc.perform(delete("/api/users/$targetId/access"))
                .andExpect(status().isBadRequest)

            verify(exactly = 0) { userOffboardingService.revokeNamespaceAccess(any(), any()) }
        }
    }
}
