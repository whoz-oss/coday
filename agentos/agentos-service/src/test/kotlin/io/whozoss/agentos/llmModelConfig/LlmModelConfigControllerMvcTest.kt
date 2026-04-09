package io.whozoss.agentos.llmModelConfig

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
 * MVC-layer test for [LlmModelConfigController] — verifies that Bean Validation is
 * triggered by the Spring MVC dispatcher on create and update endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LlmModelConfigControllerMvcTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var llmModelConfigService: LlmModelConfigService

    private val llmConfigId = UUID.randomUUID()

    init {

        "POST /api/llm-model-configs with missing llmConfigId returns 400" {
            mockMvc.perform(
                post("/api/llm-model-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "apiName": "claude-haiku-4-5" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/llm-model-configs with blank apiName returns 400" {
            mockMvc.perform(
                post("/api/llm-model-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "llmConfigId": "$llmConfigId", "apiName": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/llm-model-configs with valid minimal payload returns 201" {
            mockMvc.perform(
                post("/api/llm-model-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "llmConfigId": "$llmConfigId", "apiName": "claude-haiku-4-5" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/llm-model-configs with full payload returns 201" {
            val providerId = UUID.randomUUID()
            mockMvc.perform(
                post("/api/llm-model-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "llmConfigId": "$providerId",
                            "apiName": "claude-opus-4-6",
                            "alias": "BIG",
                            "displayName": "Claude Opus",
                            "temperature": 0.7,
                            "maxTokens": 4096
                        }
                    """)
            ).andExpect(status().isCreated)
        }

        "PUT /api/llm-model-configs/{id} with blank apiName returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/llm-model-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "llmConfigId": "$llmConfigId", "apiName": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/llm-model-configs/{id} with missing llmConfigId returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/llm-model-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "apiName": "claude-haiku-4-5" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/llm-model-configs/{id} with valid payload returns 200" {
            val created = llmModelConfigService.create(
                LlmModelConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    llmConfigId = llmConfigId,
                    apiName = "gpt-4o-to-update",
                )
            )
            mockMvc.perform(
                put("/api/llm-model-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "llmConfigId": "$llmConfigId", "apiName": "gpt-4o-to-update", "alias": "BIG" }""")
            ).andExpect(status().isOk)
        }
    }
}
