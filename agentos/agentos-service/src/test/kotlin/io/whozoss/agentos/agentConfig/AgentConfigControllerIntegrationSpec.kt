package io.whozoss.agentos.agentConfig

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
 * MVC-layer test for [AgentConfigController] — verifies that Bean Validation is
 * triggered by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence so no external services are needed.
 *
 * These tests complement [AgentConfigControllerSpec], which exercises the controller
 * logic directly without a Spring context (and therefore cannot test @Valid activation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AgentConfigControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var agentConfigService: AgentConfigService

    private val namespaceId = UUID.randomUUID()

    init {

        // -------------------------------------------------------------------------
        // POST /api/agent-configs — create
        // -------------------------------------------------------------------------

        "POST /api/agent-configs with blank name returns 400" {
            mockMvc
                .perform(
                    post("/api/agent-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/agent-configs with valid minimal payload returns 201" {
            mockMvc
                .perform(
                    post("/api/agent-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "my-agent" }"""),
                ).andExpect(status().isCreated)
        }

        "POST /api/agent-configs with all fields returns 201" {
            mockMvc
                .perform(
                    post("/api/agent-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "namespaceId": "$namespaceId",
                                "name": "full-agent",
                                "description": "A fully configured agent",
                                "instructions": "You are a helpful assistant.",
                                "modelName": "BIG"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
        }

        "POST /api/agent-configs without advancedExecution defaults to false" {
            mockMvc
                .perform(
                    post("/api/agent-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "no-advanced" }"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.advancedExecution").doesNotExist())
        }

        "POST /api/agent-configs with advancedExecution=true returns true" {
            mockMvc
                .perform(
                    post("/api/agent-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "advanced-agent", "advancedExecution": true }"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.advancedExecution").value(true))
        }

        // -------------------------------------------------------------------------
        // PUT /api/agent-configs/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/agent-configs/{id} with blank name returns 400" {
            val id = UUID.randomUUID()

            mockMvc
                .perform(
                    put("/api/agent-configs/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "PUT /api/agent-configs/{id} with valid payload returns 200" {
            val created =
                agentConfigService.create(
                    AgentConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "initial-agent",
                    ),
                )

            mockMvc
                .perform(
                    put("/api/agent-configs/${created.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "id": "${created.id}",
                                "namespaceId": "$namespaceId",
                                "name": "updated-agent",
                                "modelName": "SMALL"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // GET /api/agent-configs/platform
        // -------------------------------------------------------------------------

        "GET /api/agent-configs/platform returns only enabled platform agents by default" {
            agentConfigService.create(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "enabled-platform",
                    enabled = true,
                ),
            )
            agentConfigService.create(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "disabled-platform",
                    enabled = false,
                ),
            )

            mockMvc
                .perform(get("/api/agent-configs/platform"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[0].name").value("enabled-platform"))
        }

        "GET /api/agent-configs/platform?withDisabled=true returns disabled platform agents" {
            // The DB is shared across tests in this spec — do not assert on total count.
            // Instead verify that a disabled agent created here is present in the response.
            val disabledName = "platform-disabled-${UUID.randomUUID()}"
            agentConfigService.create(
                AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = null, name = disabledName, enabled = false),
            )

            mockMvc
                .perform(get("/api/agent-configs/platform").param("withDisabled", "true"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$disabledName')]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }

        // -------------------------------------------------------------------------
        // GET /api/agent-configs/by-parentId/{namespaceId}
        // -------------------------------------------------------------------------

        "GET /api/agent-configs/by-parentId/{namespaceId} returns configs for a super-admin caller" {
            val listNamespaceId = UUID.randomUUID()
            agentConfigService.create(
                AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = listNamespaceId, name = "agent-a"),
            )
            agentConfigService.create(
                AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = listNamespaceId, name = "agent-b"),
            )

            // The response includes namespace agents UNION platform agents — assert on presence
            // of the two namespace-scoped agents rather than total count, since platform agents
            // created by other tests in this spec also appear in the result.
            mockMvc
                .perform(get("/api/agent-configs/by-parentId/$listNamespaceId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == 'agent-a')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.name == 'agent-b')]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }

        // -------------------------------------------------------------------------
        // GET /api/agent-configs/{id} — happy path
        // -------------------------------------------------------------------------

        "GET /api/agent-configs/{id} returns 200 with payload for super-admin caller" {
            val created =
                agentConfigService.create(
                    AgentConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "fetched-agent",
                        description = "loaded by id",
                    ),
                )

            mockMvc
                .perform(get("/api/agent-configs/${created.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(created.id.toString()))
                .andExpect(jsonPath("$.name").value("fetched-agent"))
                .andExpect(jsonPath("$.description").value("loaded by id"))
        }

        // -------------------------------------------------------------------------
        // DELETE /api/agent-configs/{id} — happy path
        // -------------------------------------------------------------------------

        "DELETE /api/agent-configs/{id} returns 204 when entity exists" {
            val created =
                agentConfigService.create(
                    AgentConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "to-be-deleted",
                    ),
                )

            mockMvc
                .perform(delete("/api/agent-configs/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // POST /api/agent-configs/by-ids — batch authorization (story 5-3)
        // -------------------------------------------------------------------------

        "POST /api/agent-configs/by-ids returns matching entities for super-admin (admin bypass)" {
            // The "test" profile resolves the caller to a super-admin (bootstrap
            // disabled, but the test fixture identity is admin). The new
            // implementation short-circuits permissionService.filterVisibleIds
            // for admins and returns all entities matching the requested ids.
            // Subset-filtering for non-admins is covered by the UnitSpec
            // (AgentConfigControllerUnitSpec) which mocks PermissionService directly.
            val a =
                agentConfigService.create(
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = namespaceId, name = "byid-a"),
                )
            val b =
                agentConfigService.create(
                    AgentConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = namespaceId, name = "byid-b"),
                )

            mockMvc
                .perform(
                    post("/api/agent-configs/by-ids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "ids": ["${a.id}", "${b.id}"] }"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
        }

        "POST /api/agent-configs/by-ids with empty array returns 200 with empty list" {
            mockMvc
                .perform(
                    post("/api/agent-configs/by-ids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "ids": [] }"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(0)))
        }

        // -------------------------------------------------------------------------
        // POST /api/agent-configs/search
        // -------------------------------------------------------------------------

        "POST /api/agent-configs/search with blank namespaceId returns 400" {
            mockMvc
                .perform(
                    post("/api/agent-configs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "", "userExternalId": "alice@example.com" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/agent-configs/search with blank userExternalId returns 400" {
            mockMvc
                .perform(
                    post("/api/agent-configs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "242c4c1b-a41f-4a90-9613-e13b2a51c377", "userExternalId": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/agent-configs/search with unknown user returns 404" {
            mockMvc
                .perform(
                    post("/api/agent-configs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "242c4c1b-a41f-4a90-9613-e13b2a51c377", "userExternalId": "ghost@example.com" }"""),
                ).andExpect(status().isNotFound)
        }
    }
}
