package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.namespace.Namespace
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [MembershipController] — verifies that Bean Validation is triggered
 * by the Spring MVC dispatcher and that HTTP status codes are correct.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence and permissive security mode.
 *
 * These tests complement [MembershipControllerSpec], which exercises the controller
 * logic directly without a Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MembershipControllerMvcTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var roleRepository: RoleRepository
    @Autowired lateinit var namespaceService: NamespaceService

    init {

        // -------------------------------------------------------------------------
        // POST /api/namespaces/{nsId}/members
        // -------------------------------------------------------------------------

        "POST /api/namespaces/{nsId}/members with valid body returns 201" {
            val nsId = createNamespace()
            val targetUserId = createUser("target-assign@example.com")

            mockMvc.perform(
                post("/api/namespaces/$nsId/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId": "$targetUserId", "role": "MEMBER"}"""),
            ).andExpect(status().isCreated)
        }

        "POST /api/namespaces/{nsId}/members with blank role returns 400" {
            val nsId = createNamespace()

            mockMvc.perform(
                post("/api/namespaces/$nsId/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId": "some-user", "role": ""}"""),
            ).andExpect(status().isBadRequest)
        }

        "POST /api/namespaces/{nsId}/members with missing userId returns 400" {
            val nsId = createNamespace()

            mockMvc.perform(
                post("/api/namespaces/$nsId/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role": "MEMBER"}"""),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // GET /api/namespaces/{nsId}/members
        // -------------------------------------------------------------------------

        "GET /api/namespaces/{nsId}/members returns 200" {
            val nsId = createNamespace()

            mockMvc.perform(
                get("/api/namespaces/$nsId/members"),
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // PUT /api/namespaces/{nsId}/members/{userId}
        // -------------------------------------------------------------------------

        "PUT /api/namespaces/{nsId}/members/{userId} with valid body returns 200" {
            val nsId = createNamespace()
            val targetUserId = createUser("target-update@example.com")
            roleRepository.assignNamespaceRole(targetUserId, nsId, NamespaceRole.MEMBER, "system")

            mockMvc.perform(
                put("/api/namespaces/$nsId/members/$targetUserId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId": "$targetUserId", "role": "ADMIN"}"""),
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // DELETE /api/namespaces/{nsId}/members/{userId}
        // -------------------------------------------------------------------------

        "DELETE /api/namespaces/{nsId}/members/{userId} returns 204" {
            val nsId = createNamespace()
            val targetUserId = createUser("target-delete@example.com")
            roleRepository.assignNamespaceRole(targetUserId, nsId, NamespaceRole.MEMBER, "system")

            mockMvc.perform(
                delete("/api/namespaces/$nsId/members/$targetUserId"),
            ).andExpect(status().isNoContent)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createNamespace(): String {
        val ns = namespaceService.create(
            Namespace(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "test-ns-${UUID.randomUUID()}",
            ),
        )
        return ns.id.toString()
    }

    private fun createUser(email: String): String {
        val user = userService.create(
            User(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                externalId = email,
                email = email,
            ),
        )
        return user.id.toString()
    }
}
