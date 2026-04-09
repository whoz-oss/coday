package io.whozoss.agentos.llmConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [LlmConfigController] — verifies that Bean Validation is
 * triggered by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence so no external services are needed.
 *
 * These tests complement [LlmConfigControllerSpec], which exercises the controller
 * logic directly without a Spring context (and therefore cannot test @Valid activation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LlmConfigControllerMvcTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var llmConfigService: LlmConfigService

    private val namespaceId = UUID.randomUUID()

    init {

        // -------------------------------------------------------------------------
        // POST /api/llm-configs — create
        // -------------------------------------------------------------------------

        "POST /api/llm-configs with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "anthropic", "apiType": "Anthropic" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/llm-configs with blank name returns 400" {
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "", "apiType": "Anthropic" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/llm-configs with missing apiType returns 400" {
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "anthropic" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/llm-configs with blank apiName in model returns 400" {
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "anthropic",
                            "apiType": "Anthropic",
                            "models": [{ "apiName": "" }]
                        }
                    """)
            ).andExpect(status().isBadRequest)
        }

        "POST /api/llm-configs with valid minimal payload returns 201" {
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "anthropic", "apiType": "Anthropic" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/llm-configs with full payload including models returns 201" {
            val ns = UUID.randomUUID()
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$ns",
                            "name": "anthropic",
                            "apiType": "Anthropic",
                            "apiKey": "sk-ant-api03-secret",
                            "models": [
                                { "apiName": "claude-haiku-4-5", "alias": "SMALL" },
                                { "apiName": "claude-opus-4-6", "alias": "BIG", "temperature": 0.7 }
                            ]
                        }
                    """)
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // PUT /api/llm-configs/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/llm-configs/{id} with blank name returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/llm-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "", "apiType": "Anthropic" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/llm-configs/{id} with missing apiType returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/llm-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "anthropic" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/llm-configs/{id} with valid payload returns 200" {
            val created = llmConfigService.create(
                LlmConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "openai-to-update",
                    apiType = io.whozoss.agentos.sdk.aiProvider.AiApiType.OpenAI,
                )
            )
            mockMvc.perform(
                put("/api/llm-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "id": "${created.id}",
                            "namespaceId": "$namespaceId",
                            "name": "openai-to-update",
                            "apiType": "OpenAI"
                        }
                    """)
            ).andExpect(status().isOk)
        }
    }
}
