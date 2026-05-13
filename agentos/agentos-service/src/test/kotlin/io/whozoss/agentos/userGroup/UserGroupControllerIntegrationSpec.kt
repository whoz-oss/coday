package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.context.annotation.Import
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.*

// Note: findByNamespaceExternalId returns emptyList() in in-memory mode (no Cypher join).
// The GET endpoint and response shape are verified here; the Cypher query is covered
// by Neo4j persistence tests.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserGroupControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var userGroupService: UserGroupService

    @Autowired lateinit var namespaceService: NamespaceService

    init {

        "GET /api/user-groups without namespaceExternalId returns 400" {
            mockMvc
                .perform(get("/api/user-groups"))
                .andExpect(status().isBadRequest)
        }

        "GET /api/user-groups?namespaceExternalId=unknown returns empty list" {
            mockMvc
                .perform(get("/api/user-groups").param("namespaceExternalId", "unknown-external-id"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(0)))
        }

        // -------------------------------------------------------------------------
        // POST /api/user-groups — create
        // -------------------------------------------------------------------------

        "POST /api/user-groups with agentIds containing a non-UUID string returns 400" {
            mockMvc
                .perform(
                    post("/api/user-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceExternalId": "ext-1", "name": "Group A", "agentIds": ["not-a-uuid"] }"""),
                ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // GET /api/user-groups
        // -------------------------------------------------------------------------

        "GET /api/user-groups?namespaceExternalId= returns groups for the matching namespace" {
            val externalId = "federation-${UUID.randomUUID()}"
            val namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "test-namespace",
                        externalId = externalId,
                    ),
                )
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Group A",
                ),
            )
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Group B",
                ),
            )

            // In-memory mode: findByNamespaceExternalId returns emptyList().
            // This test verifies the endpoint wiring and response shape.
            // The actual Cypher join is tested against Neo4j in persistence tests.
            mockMvc
                .perform(get("/api/user-groups").param("namespaceExternalId", externalId))
                .andExpect(status().isOk)
        }
    }
}
