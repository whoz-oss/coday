package io.whozoss.agentos.aiProvider

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
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
 * MVC-layer test for [UserAiProviderController] — verifies Bean Validation activation,
 * JSON deserialisation, the @HideOnAccessDenied → 404 translation, and apiKey masking
 * end-to-end inside a real Spring Boot context.
 *
 * Cross-user isolation is in [UserAiProviderCrossUserIsolationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAiProviderControllerMvcTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var aiProviderService: AiProviderService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val aliceId = UUID.randomUUID()
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

        // -----------------------------------------------------------------
        // 1. POST without body → 400
        // -----------------------------------------------------------------
        "POST without body returns 400" {
            mockMvc.perform(
                post("/api/user-ai-providers").contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // 2. POST with blank name → 400 Bean Validation
        // -----------------------------------------------------------------
        "POST with blank name returns 400" {
            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "", "apiType": "Anthropic" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // 3. POST with null apiType → 400 Bean Validation
        // -----------------------------------------------------------------
        "POST with null apiType returns 400" {
            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "MY_PROVIDER" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -----------------------------------------------------------------
        // 4. POST minimal valid user-global → 201, userId=me, no namespaceId, apiKey masked
        // -----------------------------------------------------------------
        "POST user-global returns 201 with userId=alice and masked apiKey" {
            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "GLOBAL_${UUID.randomUUID()}", "apiType": "Anthropic", "apiKey": "sk-ant-verylongapikey1234" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
                .andExpect(jsonPath("$.apiKey").value(maskApiKey("sk-ant-verylongapikey1234")))
        }

        // -----------------------------------------------------------------
        // 5. POST with permitted namespaceId → 201, namespaceId=X, userId=me
        // -----------------------------------------------------------------
        "POST user-namespace with READ permission returns 201" {
            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "NS_${UUID.randomUUID()}", "apiType": "Anthropic", "apiKey": "sk-ant-nsapikey1234567890" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
        }

        // -----------------------------------------------------------------
        // 6. POST with non-permitted namespaceId → 403
        // -----------------------------------------------------------------
        "POST user-namespace without READ permission returns 403" {
            val foreignNs = UUID.randomUUID()
            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$foreignNs", "name": "FOREIGN_${UUID.randomUUID()}", "apiType": "Anthropic" }"""),
            ).andExpect(status().isForbidden)
        }

        // -----------------------------------------------------------------
        // 7. POST duplicate triple → 409
        // -----------------------------------------------------------------
        "POST duplicate (namespaceId, userId, name) returns 409" {
            val name = "DUP_${UUID.randomUUID()}"
            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$name", "apiType": "Anthropic" }"""),
            ).andExpect(status().isCreated)

            mockMvc.perform(
                post("/api/user-ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$name", "apiType": "Anthropic" }"""),
            ).andExpect(status().isConflict)
        }

        // -----------------------------------------------------------------
        // 8. PUT changing userId/namespaceId/apiType → 200, immutables preserved
        // -----------------------------------------------------------------
        "PUT preserves immutable fields even when body sets others" {
            val created = aiProviderService.create(
                io.whozoss.agentos.sdk.aiProvider.AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "PUT_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                    apiKey = "sk-ant-original123456789",
                ),
            )
            val attackerUserId = UUID.randomUUID()

            mockMvc.perform(
                put("/api/user-ai-providers/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{ "id": "${created.id}", "userId": "$attackerUserId", "namespaceId": "${UUID.randomUUID()}", "name": "${created.name}", "apiType": "OpenAI", "description": "updated" }""",
                    ),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
                .andExpect(jsonPath("$.apiType").value("Anthropic"))
                .andExpect(jsonPath("$.description").value("updated"))
        }

        // -----------------------------------------------------------------
        // 9. PUT with masked apiKey → 200, persisted apiKey unchanged
        // -----------------------------------------------------------------
        "PUT with masked apiKey preserves the persisted key" {
            val originalKey = "sk-ant-original123456789"
            val created = aiProviderService.create(
                io.whozoss.agentos.sdk.aiProvider.AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "MASKED_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                    apiKey = originalKey,
                ),
            )

            mockMvc.perform(
                put("/api/user-ai-providers/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${created.name}", "apiType": "Anthropic", "apiKey": "sk-a****wxyz" }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.apiKey").value(maskApiKey(originalKey)))

            val afterUpdate = aiProviderService.findById(created.id)!!
            afterUpdate.apiKey shouldBe originalKey
        }

        // -----------------------------------------------------------------
        // 10. DELETE on own row → 204
        // -----------------------------------------------------------------
        "DELETE on own row returns 204" {
            val created = aiProviderService.create(
                io.whozoss.agentos.sdk.aiProvider.AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "DEL_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                ),
            )
            mockMvc.perform(delete("/api/user-ai-providers/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -----------------------------------------------------------------
        // 11. GET non-existent → 404 (not 403)
        // -----------------------------------------------------------------
        "GET non-existent id returns 404 not 403" {
            mockMvc.perform(get("/api/user-ai-providers/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // 12. LIST returns paginated envelope
        // -----------------------------------------------------------------
        "LIST returns paginated JSON envelope with content/page/size/totalElements/totalPages" {
            val tag = "LIST_${UUID.randomUUID()}"
            aiProviderService.create(
                io.whozoss.agentos.sdk.aiProvider.AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "$tag-A",
                    apiType = AiApiType.Anthropic,
                ),
            )

            mockMvc.perform(get("/api/user-ai-providers?size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content").isArray)
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.totalPages").exists())
        }
    }
}
