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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
 * MVC-layer test for [UserAiModelController] — verifies Bean Validation activation,
 * JSON deserialisation, and that @HideOnAccessDenied → 404 translation works end-to-end.
 *
 * Cross-user isolation is in [UserAiModelCrossUserIsolationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAiModelControllerMvcTest : StringSpec() {
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
    private val namespaceId = UUID.randomUUID()

    init {
        beforeEach {
            every { userService.getCurrentUser() } returns alice
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true
        }

        fun createAliceProvider(nsId: UUID? = null): AiProvider =
            aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = nsId,
                    userId = aliceId,
                    name = "ALICE_PROV_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                ),
            )

        fun createBobProvider(): AiProvider =
            aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = bobId,
                    name = "BOB_PROV_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                ),
            )

        fun createNsOnlyProvider(): AiProvider =
            aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    userId = null,
                    name = "NS_PROV_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                ),
            )

        // -----------------------------------------------------------------
        // 1. POST without body → 400
        // -----------------------------------------------------------------
        "POST without body returns 400" {
            mockMvc.perform(
                post("/api/user-ai-models").contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // 2. POST with blank apiModelName → 400 Bean Validation
        // -----------------------------------------------------------------
        "POST with blank apiModelName returns 400" {
            val parent = createAliceProvider()
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${parent.id}", "apiModelName": "" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // 3. POST without aiProviderId → 400 Bean Validation
        // -----------------------------------------------------------------
        "POST without aiProviderId returns 400" {
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "apiModelName": "claude-haiku-4-5" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // 4. POST with parent that belongs to alice → 201, response has namespaceId/userId from parent
        // -----------------------------------------------------------------
        "POST with alice's own parent returns 201 with inherited namespaceId and userId" {
            val parent = createAliceProvider(nsId = namespaceId)
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${parent.id}", "apiModelName": "claude-haiku-4-5-${UUID.randomUUID()}", "alias": "SMALL_${UUID.randomUUID()}" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
                .andExpect(jsonPath("$.aiProviderId").value(parent.id.toString()))
        }

        // -----------------------------------------------------------------
        // 5. POST with bob's parent → 404 via @HideOnAccessDenied
        // -----------------------------------------------------------------
        "POST with bob's parent returns 404 (existence-hiding)" {
            val bobProvider = createBobProvider()
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${bobProvider.id}", "apiModelName": "gpt-4o" }"""),
            ).andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // 6. POST with namespace-only parent → 403 explicit
        // -----------------------------------------------------------------
        "POST with namespace-only parent returns 403" {
            val nsProvider = createNsOnlyProvider()
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${nsProvider.id}", "apiModelName": "claude-haiku-4-5" }"""),
            ).andExpect(status().isForbidden)
        }

        // -----------------------------------------------------------------
        // 7. POST with non-existent parent → 404
        // -----------------------------------------------------------------
        "POST with non-existent parent returns 404" {
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${UUID.randomUUID()}", "apiModelName": "claude-haiku-4-5" }"""),
            ).andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // 8. POST duplicate alias → 409
        // -----------------------------------------------------------------
        "POST duplicate (aiProviderId, alias) returns 409" {
            val parent = createAliceProvider()
            val alias = "ALIAS_${UUID.randomUUID()}"
            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${parent.id}", "apiModelName": "model-a", "alias": "$alias" }"""),
            ).andExpect(status().isCreated)

            mockMvc.perform(
                post("/api/user-ai-models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "aiProviderId": "${parent.id}", "apiModelName": "model-b", "alias": "$alias" }"""),
            ).andExpect(status().isConflict)
        }

        // -----------------------------------------------------------------
        // 9. PUT changing aiProviderId/userId/namespaceId → 200, immutables preserved
        // -----------------------------------------------------------------
        "PUT preserves aiProviderId, userId, namespaceId even when body sets others" {
            val parent = createAliceProvider(nsId = namespaceId)
            val created = aiModelService.create(
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = parent.id,
                    namespaceId = namespaceId,
                    userId = aliceId,
                    apiModelName = "claude-haiku-4-5",
                    alias = "PUT_TEST_${UUID.randomUUID()}",
                ),
            )

            mockMvc.perform(
                put("/api/user-ai-models/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{ "aiProviderId": "${UUID.randomUUID()}", "apiModelName": "gpt-4o", "alias": "${created.alias}", "description": "updated" }""",
                    ),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.aiProviderId").value(parent.id.toString()))
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
                .andExpect(jsonPath("$.apiModelName").value("gpt-4o"))
                .andExpect(jsonPath("$.description").value("updated"))
        }

        // -----------------------------------------------------------------
        // 10. DELETE → 204
        // -----------------------------------------------------------------
        "DELETE on own row returns 204" {
            val parent = createAliceProvider()
            val created = aiModelService.create(
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = parent.id,
                    namespaceId = null,
                    userId = aliceId,
                    apiModelName = "claude-haiku-4-5",
                    alias = "DEL_${UUID.randomUUID()}",
                ),
            )
            mockMvc.perform(delete("/api/user-ai-models/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -----------------------------------------------------------------
        // 11. GET non-existent → 404
        // -----------------------------------------------------------------
        "GET non-existent id returns 404" {
            mockMvc.perform(get("/api/user-ai-models/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // 12. LIST with ?aiProviderId filter → filtered by provider
        // -----------------------------------------------------------------
        "LIST with aiProviderId filter returns only models under that provider" {
            val parent1 = createAliceProvider()
            val parent2 = createAliceProvider()
            val tag = UUID.randomUUID()
            aiModelService.create(
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = parent1.id,
                    namespaceId = null,
                    userId = aliceId,
                    apiModelName = "model-in-parent1-$tag",
                    alias = "P1_${tag}",
                ),
            )
            aiModelService.create(
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = parent2.id,
                    namespaceId = null,
                    userId = aliceId,
                    apiModelName = "model-in-parent2-$tag",
                    alias = "P2_${tag}",
                ),
            )

            mockMvc.perform(get("/api/user-ai-models?aiProviderId=${parent1.id}&size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[?(@.aiProviderId != '${parent1.id}')]").isEmpty)
        }
    }
}
