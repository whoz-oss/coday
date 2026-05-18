package io.whozoss.agentos.aiProvider

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
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
 * MVC-layer test for the unified [AiProviderController] — verifies Bean Validation
 * activation, JSON deserialisation, the @HideOnAccessDenied → 404 translation,
 * apiKey masking, and the implicit-scope dispatch on `POST` end-to-end inside a real
 * Spring Boot context.
 *
 * Absorbs the legacy `UserAiProviderControllerMvcTest` (Decision 19 + Task 5 of
 * `tech-spec-unify-ns-user-crud-controllers.md`). Cross-user isolation is in
 * [AiProviderCrossUserIsolationSpec] ; apiKey log-scan in [AiProviderApiKeyLogScanSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AiProviderControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var aiProviderService: AiProviderService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService
    @MockkBean(relaxed = true) lateinit var namespaceService: NamespaceService

    private val aliceId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    private val namespaceId = UUID.randomUUID()
    private val ns = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        externalId = "ns-${namespaceId}",
        name = "ns",
    )

    init {
        beforeEach {
            every { userService.getCurrentUser() } returns alice
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every { namespaceService.findById(namespaceId) } returns ns
            // alice is namespace ADMIN — both READ + WRITE granted on `namespaceId`.
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
            } returns true
        }

        // -------------------------------------------------------------------------
        // POST — Bean Validation
        // -------------------------------------------------------------------------

        "POST /api/ai-providers without body returns 400" {
            mockMvc.perform(
                post("/api/ai-providers").contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-providers with blank name returns 400" {
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "", "apiType": "Anthropic" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST /api/ai-providers with missing apiType returns 400" {
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "anthropic" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // POST — Decision 15 implicit scope dispatch
        // -------------------------------------------------------------------------

        "POST with neither namespaceId nor userId returns 400 (must provide one or both)" {
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "anthropic", "apiType": "Anthropic" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST NS-shared (namespaceId only) returns 201" {
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "shared-${UUID.randomUUID()}", "apiType": "Anthropic" }"""),
            ).andExpect(status().isCreated)
        }

        "POST user-global (userId=me) returns 201 with userId=alice and masked apiKey" {
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "userId": "$aliceId", "name": "GLOBAL_${UUID.randomUUID()}", "apiType": "Anthropic", "apiKey": "sk-ant-verylongapikey1234" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
                .andExpect(jsonPath("$.apiKey").value(maskApiKey("sk-ant-verylongapikey1234")))
        }

        "POST user-namespace with READ permission returns 201" {
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userId": "$aliceId", "name": "NS_${UUID.randomUUID()}", "apiType": "Anthropic", "apiKey": "sk-ant-nsapikey1234567890" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
        }

        "POST user-namespace without READ permission returns 403" {
            val foreignNs = UUID.randomUUID()
            every { namespaceService.findById(foreignNs) } returns Namespace(
                metadata = EntityMetadata(id = foreignNs),
                externalId = "foreign-$foreignNs",
                name = "foreign",
            )

            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$foreignNs", "userId": "$aliceId", "name": "FOREIGN_${UUID.randomUUID()}", "apiType": "Anthropic" }"""),
            ).andExpect(status().isForbidden)
        }

        "POST with mismatched body.userId returns 400 (mass-assignment guard)" {
            val attackerUserId = UUID.randomUUID()
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "userId": "$attackerUserId", "name": "ATTACK_${UUID.randomUUID()}", "apiType": "Anthropic" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST with dangling namespaceId returns 404 for an authorised caller" {
            // Phase 3 (authz) runs BEFORE Phase 3.5 (existence) — closes the existence-leak
            // for non-members. To exercise Phase 3.5 we must therefore grant READ on the
            // (still nonexistent) namespace, simulating a super-admin who passes Phase 3 and
            // hits the dangling-FK guard.
            val unknownNs = UUID.randomUUID()
            every { namespaceService.findById(unknownNs) } returns null
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.READ)
            } returns true

            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$unknownNs", "userId": "$aliceId", "name": "ORPHAN_${UUID.randomUUID()}", "apiType": "Anthropic" }"""),
            ).andExpect(status().isNotFound)
        }

        "POST with dangling namespaceId returns 403 for a non-member (existence-leak guard)" {
            val unknownNs = UUID.randomUUID()
            every { namespaceService.findById(unknownNs) } returns null
            // No READ grant for alice on unknownNs : Phase 3 fires first → 403, no leak.

            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$unknownNs", "userId": "$aliceId", "name": "ORPHAN_${UUID.randomUUID()}", "apiType": "Anthropic" }"""),
            ).andExpect(status().isForbidden)
        }

        "POST duplicate (namespaceId, userId, name) returns 409" {
            val name = "DUP_${UUID.randomUUID()}"
            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userId": "$aliceId", "name": "$name", "apiType": "Anthropic" }"""),
            ).andExpect(status().isCreated)

            mockMvc.perform(
                post("/api/ai-providers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userId": "$aliceId", "name": "$name", "apiType": "Anthropic" }"""),
            ).andExpect(status().isConflict)
        }

        // -------------------------------------------------------------------------
        // PUT — Bean Validation + immutable fields
        // -------------------------------------------------------------------------

        "PUT /api/ai-providers/{id} with blank name returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/ai-providers/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "", "apiType": "Anthropic" }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/ai-providers/{id} with valid payload returns 200" {
            // Create owned by alice so the evaluator's ownership branch grants WRITE
            // (PermissionService is fully mocked here, so the namespace-membership
            // path never resolves to true at the entity level).
            val created = aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "openai-to-update-${UUID.randomUUID()}",
                    apiType = AiApiType.OpenAI,
                ),
            )

            mockMvc.perform(
                put("/api/ai-providers/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "name": "${created.name}", "apiType": "OpenAI" }"""),
            ).andExpect(status().isOk)
        }

        "PUT preserves immutable fields (userId, namespaceId, apiType) even when body sets others" {
            val created = aiProviderService.create(
                AiProvider(
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
                put("/api/ai-providers/${created.id}")
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

        "PUT with masked apiKey preserves the persisted key" {
            val originalKey = "sk-ant-original123456789"
            val created = aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "MASKED_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                    apiKey = originalKey,
                ),
            )

            mockMvc.perform(
                put("/api/ai-providers/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${created.name}", "apiType": "Anthropic", "apiKey": "sk-a****wxyz" }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.apiKey").value(maskApiKey(originalKey)))

            val afterUpdate = aiProviderService.findById(created.id)!!
            afterUpdate.apiKey shouldBe originalKey
        }

        // -------------------------------------------------------------------------
        // DELETE
        // -------------------------------------------------------------------------

        "DELETE on own row returns 204" {
            val created = aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "DEL_${UUID.randomUUID()}",
                    apiType = AiApiType.Anthropic,
                ),
            )
            mockMvc.perform(delete("/api/ai-providers/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // GET / LIST
        // -------------------------------------------------------------------------

        "GET non-existent id returns 404 not 403" {
            mockMvc.perform(get("/api/ai-providers/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        "LIST returns a flat JSON array" {
            val tag = "LIST_${UUID.randomUUID()}"
            aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "$tag-A",
                    apiType = AiApiType.Anthropic,
                ),
            )

            mockMvc.perform(get("/api/ai-providers"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }
    }
}
