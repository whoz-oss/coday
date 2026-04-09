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

        "POST /api/llm-configs with valid minimal payload returns 201" {
            mockMvc.perform(
                post("/api/llm-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "anthropic", "apiType": "Anthropic" }""")
            ).andExpect(status().isCreated)
        }

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
                    .content("""{ "id": "${created.id}", "namespaceId": "$namespaceId", "name": "openai-to-update", "apiType": "OpenAI" }""")
            ).andExpect(status().isOk)
        }
    }
}
