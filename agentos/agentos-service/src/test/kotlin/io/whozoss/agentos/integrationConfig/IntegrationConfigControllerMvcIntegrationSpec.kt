package io.whozoss.agentos.integrationConfig

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
 * MVC-layer test for [IntegrationConfigController] — verifies that Bean Validation is
 * triggered by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence so no external services are needed.
 *
 * These tests complement [IntegrationConfigControllerSpec], which exercises the
 * controller logic directly without a Spring context (and therefore cannot test
 * @Valid activation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationConfigControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var integrationConfigService: IntegrationConfigService

    private val namespaceId = UUID.randomUUID()

    init {

        // -------------------------------------------------------------------------
        // POST /api/integration-configs — create
        // -------------------------------------------------------------------------

        "POST /api/integration-configs with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "JIRA_PROD", "integrationType": "JIRA" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/integration-configs with blank name returns 400" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "", "integrationType": "JIRA" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/integration-configs with blank integrationType returns 400" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "JIRA_PROD", "integrationType": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/integration-configs with valid payload returns 201" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "JIRA_PROD", "integrationType": "JIRA" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/integration-configs with parameters returns 201" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "SLACK_DEV", "integrationType": "SLACK", "parameters": { "webhookUrl": "https://hooks.slack.com/xxx" } }""")
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // PUT /api/integration-configs/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/integration-configs/{id} with blank name returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/integration-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "", "integrationType": "JIRA" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/integration-configs/{id} with blank integrationType returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/integration-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "JIRA_PROD", "integrationType": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/integration-configs/{id} with valid payload returns 200" {
            // Pre-create the entity so the update finds it
            val created = integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "GITHUB_MAIN",
                    integrationType = "GITHUB",
                )
            )

            mockMvc.perform(
                put("/api/integration-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "namespaceId": "$namespaceId", "name": "GITHUB_MAIN", "integrationType": "GITHUB" }""")
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // GET /api/integration-configs/by-parentId/{namespaceId}
        // -------------------------------------------------------------------------

        "GET /api/integration-configs/by-parentId/{namespaceId} returns configs for a super-admin caller" {
            val listNamespaceId = UUID.randomUUID()
            integrationConfigService.create(
                IntegrationConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = listNamespaceId, name = "JIRA_A", integrationType = "JIRA"),
            )
            integrationConfigService.create(
                IntegrationConfig(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = listNamespaceId, name = "SLACK_B", integrationType = "SLACK"),
            )

            mockMvc.perform(get("/api/integration-configs/by-parentId/$listNamespaceId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
        }
    }
}
