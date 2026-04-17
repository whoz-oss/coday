package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiProviderControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var aiProviderService: AiProviderService

    private val namespaceId = UUID.randomUUID()

    init {

        "POST /api/ai-providers with blank name returns 400" {
            mockMvc
                .perform(
                    post("/api/ai-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "", "apiType": "Anthropic" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-providers with missing apiType returns 400" {
            mockMvc
                .perform(
                    post("/api/ai-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "anthropic" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-providers with neither namespaceId nor userId returns 400" {
            mockMvc
                .perform(
                    post("/api/ai-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "anthropic", "apiType": "Anthropic" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-providers with namespaceId only returns 201" {
            mockMvc
                .perform(
                    post("/api/ai-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "anthropic", "apiType": "Anthropic" }"""),
                ).andExpect(status().isCreated)
        }

        "POST /api/ai-providers with userId only returns 201" {
            val userId = UUID.randomUUID()
            mockMvc
                .perform(
                    post("/api/ai-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "userId": "$userId", "name": "anthropic", "apiType": "Anthropic" }"""),
                ).andExpect(status().isCreated)
        }

        "PUT /api/ai-providers/{id} with blank name returns 400" {
            val id = UUID.randomUUID()
            mockMvc
                .perform(
                    put("/api/ai-providers/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "", "apiType": "Anthropic" }"""),
                ).andExpect(status().isBadRequest)
        }

        "PUT /api/ai-providers/{id} with valid payload returns 200" {
            val created =
                aiProviderService.create(
                    AiProvider(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "openai-to-update",
                        apiType = AiApiType.OpenAI,
                    ),
                )
            mockMvc
                .perform(
                    put("/api/ai-providers/${created.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{ "id": "${created.id}", "namespaceId": "$namespaceId", "name": "openai-to-update", "apiType": "OpenAI" }""",
                        ),
                ).andExpect(status().isOk)
        }
    }
}
