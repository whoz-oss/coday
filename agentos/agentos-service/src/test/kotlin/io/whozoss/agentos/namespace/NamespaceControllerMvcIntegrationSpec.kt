package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [NamespaceController] — verifies Bean Validation and   * security flows through the full Spring MVC dispatcher.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * (in-memory persistence). The OS user is auto-promoted to super-admin on first
 * access (first-user bootstrap), so positive CRUD paths succeed without extra setup.
 *
 * These tests complement [NamespaceControllerSpec], which covers negative paths
 * (403 for non-super-admin) at the unit level.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class NamespaceControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService

    init {

        // -------------------------------------------------------------------------
        // POST /api/namespaces — create (Bean Validation + super-admin gate)
        // -------------------------------------------------------------------------

        "POST /api/namespaces with blank name returns 400" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/namespaces with valid name returns 201 (OS user is super-admin)" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "engineering" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/namespaces with valid configPath returns 201" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "coday", "configPath": "/opt/coday" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/namespaces with path-traversal in configPath returns 201" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "coday", "configPath": "../../etc/passwd" }""")
            ).andExpect(status().isCreated)
        }

        // NOTE: Verifying the auto-ADMIN grant via [PermissionService.listEntitiesForUser]
        // is not possible in this test class because the "test" profile uses
        // [InMemoryPermissionServiceImpl], a permissive no-op stub. The auto-grant
        // behaviour is covered exhaustively by the mocked unit tests in
        // [NamespaceControllerSpec].

        // -------------------------------------------------------------------------
        // PUT /api/namespaces/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/namespaces/{id} with blank name returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/namespaces/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/namespaces/{id} with valid payload returns 200 (super-admin bypass)" {
            val created = namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "platform",
                )
            )

            mockMvc.perform(
                put("/api/namespaces/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "name": "platform-updated", "configPath": "/opt/platform" }""")
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // DELETE /api/namespaces/{id} — super-admin only + cascade
        // -------------------------------------------------------------------------

        "DELETE /api/namespaces/{id} returns 204 for super-admin" {
            val created = namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "to-delete",
                )
            )

            mockMvc.perform(delete("/api/namespaces/${created.id}"))
                .andExpect(status().isNoContent)
        }

        "DELETE /api/namespaces/{id} returns 404 when namespace does not exist" {
            val missingId = UUID.randomUUID()
            mockMvc.perform(delete("/api/namespaces/$missingId"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // GET /api/namespaces — listAll
        // -------------------------------------------------------------------------

        "GET /api/namespaces returns items with role=SUPER-ADMIN for the super-admin caller" {
            namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "list-probe-${UUID.randomUUID()}"),
            )

            mockMvc.perform(get("/api/namespaces"))
                .andExpect(status().isOk)
                // At least one item must be present (we just created one)
                .andExpect(jsonPath("$[0].role").value("SUPER-ADMIN"))
                // Every item returned must carry role = "SUPER-ADMIN"
                .andExpect(jsonPath("$[*].role", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("SUPER-ADMIN"))))
        }

        // -------------------------------------------------------------------------
        // POST /api/namespaces/by-ids — batch authorization (story 5-3)
        // -------------------------------------------------------------------------

        "POST /api/namespaces/by-ids returns matching namespaces for super-admin (admin bypass)" {
            // The "test" profile resolves the caller to a super-admin. The new
            // by-ids implementation short-circuits permissionService.filterVisibleIds
            // for admins and returns all entities matching the requested ids.
            // Subset-filtering for non-admins is covered by the UnitSpec
            // (NamespaceControllerSpec) which mocks the permission lookup directly.
            val a = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "byid-a-${UUID.randomUUID()}"),
            )
            val b = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "byid-b-${UUID.randomUUID()}"),
            )

            mockMvc.perform(
                post("/api/namespaces/by-ids")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""["${a.id}", "${b.id}"]"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
        }

        "POST /api/namespaces/by-ids with empty array returns 200 with empty list" {
            mockMvc.perform(
                post("/api/namespaces/by-ids")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""[]"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(0)))
        }
    }
}
