package io.whozoss.agentos.aiModelConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AiModelControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var aiModelService: AiModelService

    @Autowired lateinit var aiProviderService: AiProviderService

    private val namespaceId = UUID.randomUUID()

    private fun createParentAiProvider(nsId: UUID = namespaceId): AiProvider =
        aiProviderService.create(
            AiProvider(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = nsId,
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
                        .content("""{ "aiProviderId": "${parent.id}", "apiModelName": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-models with valid payload returns 201" {
            val parent = createParentAiProvider()
            mockMvc
                .perform(
                    post("/api/ai-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "aiProviderId": "${parent.id}", "apiModelName": "claude-haiku-4-5" }"""),
                ).andExpect(status().isCreated)
        }

        "PUT /api/ai-models/{id} with blank apiName returns 400" {
            val id = UUID.randomUUID()
            val parent = createParentAiProvider()
            mockMvc
                .perform(
                    put("/api/ai-models/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "$id", "aiProviderId": "${parent.id}", "apiModelName": "" }"""),
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
                        apiModelName = "gpt-4o-to-update",
                    ),
                )
            mockMvc
                .perform(
                    put("/api/ai-models/${created.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{ "id": "${created.id}", "aiProviderId": "${parent.id}", "apiModelName": "gpt-4o-to-update", "alias": "BIG" }""",
                        ),
                ).andExpect(status().isOk)
        }

        // Hard-break: /by-parentId/ is now a stub returning 404 (use ?aiProviderId= instead)
        "GET /api/ai-models/by-parentId/{providerId} returns 404 (endpoint removed)" {
            val parent = createParentAiProvider()
            mockMvc.perform(get("/api/ai-models/by-parentId/${parent.id}"))
                .andExpect(status().isNotFound)
        }

        // Hard-break: /by-namespaceId/ is fully removed (use ?namespaceId= instead)
        "GET /api/ai-models/by-namespaceId/{namespaceId} returns 404 (endpoint removed)" {
            mockMvc.perform(get("/api/ai-models/by-namespaceId/$namespaceId"))
                .andExpect(status().isNotFound)
        }
    }
}
