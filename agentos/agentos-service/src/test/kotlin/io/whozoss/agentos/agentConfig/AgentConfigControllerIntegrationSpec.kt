package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
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
@ActiveProfiles("test")
class AgentConfigControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var agentConfigService: AgentConfigService

    private val namespaceId = UUID.randomUUID()

    init {

        // -------------------------------------------------------------------------
        // POST /api/agent-configs — create
        // -------------------------------------------------------------------------

        "POST /api/agent-configs with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/agent-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "my-agent" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/agent-configs with blank name returns 400" {
            mockMvc.perform(
                post("/api/agent-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/agent-configs with valid minimal payload returns 201" {
            mockMvc.perform(
                post("/api/agent-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "my-agent" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/agent-configs with all fields returns 201" {
            mockMvc.perform(
                post("/api/agent-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "full-agent",
                            "description": "A fully configured agent",
                            "instructions": "You are a helpful assistant.",
                            "modelName": "BIG"
                        }
                    """.trimIndent())
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // PUT /api/agent-configs/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/agent-configs/{id} with blank name returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/agent-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/agent-configs/{id} with valid payload returns 200" {
            val created = agentConfigService.create(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "initial-agent",
                )
            )

            mockMvc.perform(
                put("/api/agent-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "id": "${created.id}",
                            "namespaceId": "$namespaceId",
                            "name": "updated-agent",
                            "modelName": "SMALL"
                        }
                    """.trimIndent())
            ).andExpect(status().isOk)
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

            mockMvc.perform(get("/api/agent-configs/by-parentId/$listNamespaceId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
        }
    }
}
