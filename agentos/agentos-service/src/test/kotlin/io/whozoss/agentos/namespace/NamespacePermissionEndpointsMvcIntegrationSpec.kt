package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [NamespacePermissionEndpoints].
 *
 * In the "test" profile ([io.whozoss.agentos.permissions.InMemoryPermissionServiceImpl]
 * is used), the permission service is a permissive no-op: every
 * [io.whozoss.agentos.permissions.PermissionService.hasPermission] call returns true
 * and grant/revoke are no-ops. This lets us exercise the full MVC wiring but means
 * 403 flows cannot be validated here — they live in [NamespacePermissionEndpointsSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NamespacePermissionEndpointsMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var userService: UserService

    init {

        // -------------------------------------------------------------------------
        // PUT /api/namespaces/{id}/admins/{userId}
        // -------------------------------------------------------------------------

        "PUT /api/namespaces/{id}/admins/{userId} returns 200 when namespace and user exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-put-ok"),
            )
            val target = userService.resolveOrCreateByExternalId("put-ok-${UUID.randomUUID()}@example.com")

            mockMvc.perform(put("/api/namespaces/${namespace.id}/admins/${target.id}"))
                .andExpect(status().isOk)
        }

        "PUT returns 404 when namespace does not exist" {
            val target = userService.resolveOrCreateByExternalId("put-404ns-${UUID.randomUUID()}@example.com")
            val missingNamespaceId = UUID.randomUUID()

            mockMvc.perform(put("/api/namespaces/$missingNamespaceId/admins/${target.id}"))
                .andExpect(status().isNotFound)
        }

        "PUT returns 404 when target user does not exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-put-404user"),
            )
            val missingUserId = UUID.randomUUID()

            mockMvc.perform(put("/api/namespaces/${namespace.id}/admins/$missingUserId"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // DELETE /api/namespaces/{id}/admins/{userId}
        // -------------------------------------------------------------------------

        "DELETE /api/namespaces/{id}/admins/{userId} returns 204 when namespace and user exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-delete-ok"),
            )
            val target = userService.resolveOrCreateByExternalId("delete-ok-${UUID.randomUUID()}@example.com")

            mockMvc.perform(delete("/api/namespaces/${namespace.id}/admins/${target.id}"))
                .andExpect(status().isNoContent)
        }

        "DELETE returns 404 when namespace does not exist" {
            val target = userService.resolveOrCreateByExternalId("delete-404ns-${UUID.randomUUID()}@example.com")
            val missingNamespaceId = UUID.randomUUID()

            mockMvc.perform(delete("/api/namespaces/$missingNamespaceId/admins/${target.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE returns 404 when target user does not exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-delete-404user"),
            )
            val missingUserId = UUID.randomUUID()

            mockMvc.perform(delete("/api/namespaces/${namespace.id}/admins/$missingUserId"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // PUT /api/namespaces/{id}/members/{userId}
        // -------------------------------------------------------------------------

        "PUT /api/namespaces/{id}/members/{userId} returns 200 when namespace and user exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-put-member-ok"),
            )
            val target = userService.resolveOrCreateByExternalId("put-member-ok-${UUID.randomUUID()}@example.com")

            mockMvc.perform(put("/api/namespaces/${namespace.id}/members/${target.id}"))
                .andExpect(status().isOk)
        }

        "PUT members returns 404 when namespace does not exist" {
            val target = userService.resolveOrCreateByExternalId("put-member-404ns-${UUID.randomUUID()}@example.com")
            val missingNamespaceId = UUID.randomUUID()

            mockMvc.perform(put("/api/namespaces/$missingNamespaceId/members/${target.id}"))
                .andExpect(status().isNotFound)
        }

        "PUT members returns 404 when target user does not exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-put-member-404user"),
            )
            val missingUserId = UUID.randomUUID()

            mockMvc.perform(put("/api/namespaces/${namespace.id}/members/$missingUserId"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // DELETE /api/namespaces/{id}/members/{userId}
        // -------------------------------------------------------------------------

        "DELETE /api/namespaces/{id}/members/{userId} returns 204 when namespace and user exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-delete-member-ok"),
            )
            val target = userService.resolveOrCreateByExternalId("delete-member-ok-${UUID.randomUUID()}@example.com")

            mockMvc.perform(delete("/api/namespaces/${namespace.id}/members/${target.id}"))
                .andExpect(status().isNoContent)
        }

        "DELETE members returns 404 when namespace does not exist" {
            val target = userService.resolveOrCreateByExternalId("delete-member-404ns-${UUID.randomUUID()}@example.com")
            val missingNamespaceId = UUID.randomUUID()

            mockMvc.perform(delete("/api/namespaces/$missingNamespaceId/members/${target.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE members returns 404 when target user does not exist" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-delete-member-404user"),
            )
            val missingUserId = UUID.randomUUID()

            mockMvc.perform(delete("/api/namespaces/${namespace.id}/members/$missingUserId"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // GET /api/namespaces/{id}/users
        // -------------------------------------------------------------------------

        "GET /api/namespaces/{id}/users returns 200 with empty list for newly-created namespace" {
            val namespace = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-get-users-empty"),
            )

            // InMemoryPermissionServiceImpl.listUsersWithPermission returns emptyList always,
            // so any newly-created namespace (regardless of Neo4j state) will surface as empty here.
            mockMvc.perform(get("/api/namespaces/${namespace.id}/users"))
                .andExpect(status().isOk)
                .andExpect(content().json("[]"))
        }

        "GET /api/namespaces/{id}/users returns 404 when namespace does not exist" {
            val missingNamespaceId = UUID.randomUUID()

            mockMvc.perform(get("/api/namespaces/$missingNamespaceId/users"))
                .andExpect(status().isNotFound)
        }
    }
}
