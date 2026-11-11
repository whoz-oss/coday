package io.whozoss.agentos.aiProvider

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * AI providers and models with `namespaceId = null && userId = null` (the platform layer), on the real
 * security chain and embedded Neo4j: a super-admin creates, updates and deletes them, anyone else keeps
 * getting 403 (or 404 where the endpoint hides existence). The first-run wizard relies on this: it
 * creates the platform providers and their aliased models as the bootstrap super-admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class PlatformScopeAiProviderIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var aiProviderService: AiProviderService
    @Autowired lateinit var aiModelService: AiModelService

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private fun user(isAdmin: Boolean): User {
        val id = UUID.randomUUID()
        return User(metadata = EntityMetadata(id = id), externalId = "plat-$id@example.com", email = "plat-$id@example.com", isAdmin = isAdmin)
            .also {
                userRepository.save(it)
                every { userService.findById(it.id) } returns it
            }
    }

    private fun actAs(user: User) {
        every { userService.getCurrentUser() } returns user
    }

    private fun json(
        request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        body: String,
    ) = mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))

    private fun createProvider(name: String): String =
        json(post("/api/ai-providers"), """{ "name": "$name", "apiType": "Anthropic", "apiKey": "sk-ant-platformkey123456" }""")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.namespaceId").doesNotExist())
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andReturn()
            .response.contentAsString
            .let { Regex("\"id\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

    init {
        "a super-admin creates, updates and deletes a platform provider" {
            actAs(user(isAdmin = true))
            val name = "platform-${UUID.randomUUID()}"
            val id = createProvider(name)

            json(put("/api/ai-providers/$id"), """{ "name": "$name", "apiType": "Anthropic", "description": "updated" }""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.description").value("updated"))
            aiProviderService.findPlatformLevel().any { it.name == name } shouldBe true

            mockMvc.perform(delete("/api/ai-providers/$id")).andExpect(status().isNoContent)
            aiProviderService.findPlatformLevel().any { it.name == name } shouldBe false
        }

        "a super-admin creates, updates and deletes the models of a platform provider" {
            actAs(user(isAdmin = true))
            val providerId = createProvider("platform-${UUID.randomUUID()}")
            val alias = "BIG-${UUID.randomUUID().toString().take(6)}"

            val modelId =
                json(
                    post("/api/ai-models"),
                    """{ "aiProviderId": "$providerId", "apiModelName": "claude-x", "alias": "$alias", "priority": 2 }""",
                ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.namespaceId").doesNotExist())
                    .andExpect(jsonPath("$.userId").doesNotExist())
                    .andReturn()
                    .response.contentAsString
                    .let { Regex("\"id\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

            aiModelService.findPlatformLevel().single { it.alias == alias }.priority shouldBe 2
            json(put("/api/ai-models/$modelId"), """{ "aiProviderId": "$providerId", "apiModelName": "claude-y", "alias": "$alias", "priority": 5 }""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.priority").value(5))
            mockMvc.perform(delete("/api/ai-models/$modelId")).andExpect(status().isNoContent)
            aiModelService.findPlatformLevel().none { it.alias == alias } shouldBe true
        }

        "someone who is not a super-admin cannot create, update or delete platform providers and models" {
            actAs(user(isAdmin = true))
            val name = "platform-${UUID.randomUUID()}"
            val providerId = createProvider(name)
            val modelId =
                json(post("/api/ai-models"), """{ "aiProviderId": "$providerId", "apiModelName": "claude-x", "alias": "SMALL-${UUID.randomUUID()}" }""")
                    .andExpect(status().isCreated)
                    .andReturn()
                    .response.contentAsString
                    .let { Regex("\"id\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
            actAs(user(isAdmin = false))

            json(post("/api/ai-providers"), """{ "name": "mine-${UUID.randomUUID()}", "apiType": "Anthropic" }""").andExpect(status().isForbidden)
            json(post("/api/ai-models"), """{ "aiProviderId": "$providerId", "apiModelName": "claude-z", "alias": "X" }""").andExpect(status().isForbidden)
            json(put("/api/ai-providers/$providerId"), """{ "name": "$name", "apiType": "Anthropic", "description": "hijacked" }""")
                .andExpect(status().is4xxClientError)
            mockMvc.perform(delete("/api/ai-providers/$providerId")).andExpect(status().is4xxClientError)
            mockMvc.perform(delete("/api/ai-models/$modelId")).andExpect(status().isForbidden)

            aiProviderService.findPlatformLevel().single { it.name == name }.description shouldBe null
            aiModelService.findById(UUID.fromString(modelId)) shouldBe aiModelService.findById(UUID.fromString(modelId))
            aiModelService.findPlatformLevel().any { it.id.toString() == modelId } shouldBe true
        }

        "the api key of a platform provider is masked on read" {
            actAs(user(isAdmin = true))
            val id = createProvider("platform-${UUID.randomUUID()}")
            mockMvc
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/ai-providers/$id"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.apiKey").value(maskApiKey("sk-ant-platformkey123456")))
            aiProviderService.getById(UUID.fromString(id)).apiType shouldBe AiApiType.Anthropic
        }
    }
}
