package io.whozoss.agentos.aiModelConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
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
class AiModelControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var aiModelService: AiModelService

    @Autowired lateinit var aiProviderService: AiProviderService

    private val namespaceId = UUID.randomUUID()

    private fun createParentAiProvider(): AiProvider =
        aiProviderService.create(
            AiProvider(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = "anthropic-${UUID.randomUUID()}",
                apiType = AiApiType.Anthropic,
            ),
        )

    init {

        "POST /api/ai-models with missing aiProviderId returns 400" {
            mockMvc
                .perform(
                    post("/api/ai-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "apiName": "claude-haiku-4-5" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-models with blank apiName returns 400" {
            val parent = createParentAiProvider()
            mockMvc
                .perform(
                    post("/api/ai-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "aiProviderId": "${parent.id}", "apiName": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-models with valid payload returns 201" {
            val parent = createParentAiProvider()
            mockMvc
                .perform(
                    post("/api/ai-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "aiProviderId": "${parent.id}", "apiName": "claude-haiku-4-5" }"""),
                ).andExpect(status().isCreated)
        }

        "PUT /api/ai-models/{id} with blank apiName returns 400" {
            val id = UUID.randomUUID()
            val parent = createParentAiProvider()
            mockMvc
                .perform(
                    put("/api/ai-models/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "$id", "aiProviderId": "${parent.id}", "apiName": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "PUT /api/ai-models/{id} with valid payload returns 200" {
            val parent = createParentAiProvider()
            val created =
                aiModelService.create(
                    AiModel(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        aiProviderId = parent.id,
                        namespaceId = namespaceId,
                        apiName = "gpt-4o-to-update",
                    ),
                )
            mockMvc
                .perform(
                    put("/api/ai-models/${created.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{ "id": "${created.id}", "aiProviderId": "${parent.id}", "apiName": "gpt-4o-to-update", "alias": "BIG" }""",
                        ),
                ).andExpect(status().isOk)
        }
    }
}
