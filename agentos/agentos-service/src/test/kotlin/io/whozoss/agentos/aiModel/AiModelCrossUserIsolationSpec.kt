package io.whozoss.agentos.aiModel

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
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
 * Cross-user isolation matrix for [AiModelController] (unified `/api/ai-models`) (NFR-SEC-1, AR19).
 *
 * Covers the 4×2 matrix (GET/PUT/DELETE × user-global/user-namespace) plus:
 * - LIST isolation (alice never sees bob's models)
 * - LIST with explicit userId UUID returns 400 (only 'me' sentinel allowed)
 * - POST with bob's UserAiProvider returns 404 (existence-hiding)
 *
 * All cross-user isolation failures must return 404, never 403.
 * The `@HideOnAccessDenied` + `OwnershipResolver` branch: model.userId=bob ≠ aliceId → denied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AiModelCrossUserIsolationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var aiModelService: AiModelService
    @Autowired lateinit var aiProviderService: AiProviderService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val aliceId = UUID.randomUUID()
    private val bobId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    private val sharedNamespaceId = UUID.randomUUID()

    init {
        beforeEach {
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ)
            } returns true
            every {
                permissionService.hasPermission(bobId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ)
            } returns true
        }

        fun createBobProvider(namespaceId: UUID?): AiProvider =
            aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    userId = bobId,
                    name = "BOB_PROV_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                ),
            )

        fun createBobModel(namespaceId: UUID?, providerId: UUID): AiModel =
            aiModelService.create(
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = providerId,
                    namespaceId = namespaceId,
                    userId = bobId,
                    apiModelName = "claude-haiku-4-5",
                    alias = "BOB_MODEL_${UUID.randomUUID()}",
                ),
            )

        // -----------------------------------------------------------------
        // GET — both modes
        // -----------------------------------------------------------------
        "GET alice → bob.user-global model returns 404" {
            val bobProvider = createBobProvider(namespaceId = null)
            val bobModel = createBobModel(namespaceId = null, providerId = bobProvider.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/ai-models/${bobModel.id}"))
                .andExpect(status().isNotFound)
        }

        "GET alice → bob.user-namespace model returns 404" {
            val bobProvider = createBobProvider(namespaceId = sharedNamespaceId)
            val bobModel = createBobModel(namespaceId = sharedNamespaceId, providerId = bobProvider.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/ai-models/${bobModel.id}"))
                .andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // PUT — both modes
        // -----------------------------------------------------------------
        "PUT alice → bob.user-global model returns 404" {
            val bobProvider = createBobProvider(namespaceId = null)
            val bobModel = createBobModel(namespaceId = null, providerId = bobProvider.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/ai-models/${bobModel.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${bobProvider.id}", "apiModelName": "attack-model" }"""),
            ).andExpect(status().isNotFound)
        }

        "PUT alice → bob.user-namespace model returns 404" {
            val bobProvider = createBobProvider(namespaceId = sharedNamespaceId)
            val bobModel = createBobModel(namespaceId = sharedNamespaceId, providerId = bobProvider.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/ai-models/${bobModel.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${bobProvider.id}", "apiModelName": "attack-model" }"""),
            ).andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // DELETE — both modes
        // -----------------------------------------------------------------
        "DELETE alice → bob.user-global model returns 404" {
            val bobProvider = createBobProvider(namespaceId = null)
            val bobModel = createBobModel(namespaceId = null, providerId = bobProvider.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/ai-models/${bobModel.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE alice → bob.user-namespace model returns 404" {
            val bobProvider = createBobProvider(namespaceId = sharedNamespaceId)
            val bobModel = createBobModel(namespaceId = sharedNamespaceId, providerId = bobProvider.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/ai-models/${bobModel.id}"))
                .andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // LIST — alice never sees bob's models
        // -----------------------------------------------------------------
        "LIST as alice never includes bob's models in either mode" {
            val bobProvider1 = createBobProvider(namespaceId = null)
            val bobProvider2 = createBobProvider(namespaceId = sharedNamespaceId)
            createBobModel(namespaceId = null, providerId = bobProvider1.id)
            createBobModel(namespaceId = sharedNamespaceId, providerId = bobProvider2.id)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/ai-models?userId=me&size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[?(@.userId != '$aliceId')]").isEmpty)
        }

        // -----------------------------------------------------------------
        // LIST — userId UUID (not 'me') returns 400
        // -----------------------------------------------------------------
        "LIST as alice with ?userId=<bob.id> returns 400 (only 'me' allowed)" {
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/ai-models?userId=$bobId&size=100"))
                .andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // POST with bob's UserAiProvider → 404 (existence-hiding)
        // -----------------------------------------------------------------
        "POST alice → bob's UserAiProvider returns 404 (existence-hiding)" {
            val bobProvider = createBobProvider(namespaceId = null)
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                post("/api/ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${bobProvider.id}", "apiModelName": "claude-haiku-4-5" }"""),
            ).andExpect(status().isNotFound)
        }
    }
}
