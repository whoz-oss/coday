package io.whozoss.agentos.prompt

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
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
 * MVC-layer integration test for [PromptController].
 *
 * Verifies:
 * - Bean Validation activation on POST and PUT (@Valid on @RequestBody)
 * - Scope dispatch on POST (platform vs namespace-scoped)
 * - Authorization guards (403 for non-admin on platform, 403 for missing namespace WRITE)
 * - Existence-leak guard (authz before namespace existence check)
 * - Duplicate name returns 409
 * - Service-level validation (blank content element, duplicate parameter names) returns 400
 * - CRUD happy paths (201, 200, 204, 404)
 *
 * The "test" profile uses InMemoryPermissionServiceImpl which is a permissive no-op.
 * Authorization paths that require a denial are exercised via @MockkBean on [PermissionService]
 * and [UserService], following the pattern of [IntegrationConfigControllerMvcIntegrationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class PromptControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var promptService: PromptService

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
    private val admin = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "admin@example.com",
        email = "admin@example.com",
        isAdmin = true,
    )
    private val namespaceId = UUID.randomUUID()
    private val ns = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        externalId = "ns-$namespaceId",
        name = "ns",
    )

    init {
        beforeEach {
            every { userService.getCurrentUser() } returns alice
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every { namespaceService.findById(namespaceId) } returns ns
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
            } returns true
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true
        }

        // -------------------------------------------------------------------------
        // POST — Bean Validation
        // -------------------------------------------------------------------------

        "POST /api/prompts without body returns 400" {
            mockMvc.perform(
                post("/api/prompts").contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank name returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "", "content": ["Hello"] }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST with empty content list returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "My Prompt", "content": [] }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank parameter name returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "My Prompt",
                            "content": ["Hello"],
                            "parameters": [{ "name": "", "defaultValue": "English" }]
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank parameter defaultValue returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "My Prompt",
                            "content": ["Hello"],
                            "parameters": [{ "name": "lang", "defaultValue": "" }]
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // POST — scope dispatch and authorization
        // -------------------------------------------------------------------------

        "POST platform prompt returns 403 for non-admin" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "Global", "content": ["Hello"] }"""),
            ).andExpect(status().isForbidden)
        }

        "POST platform prompt returns 201 for super-admin" {
            every { userService.getCurrentUser() } returns admin
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "Global-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
        }

        "POST namespace prompt returns 201 when caller has WRITE on namespace" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "NS-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
        }

        "POST namespace prompt returns 403 when caller lacks WRITE" {
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
            } returns false

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Blocked", "content": ["Hello"] }"""),
            ).andExpect(status().isForbidden)
        }

        "POST with dangling namespaceId returns 404 for authorised caller" {
            val unknownNs = UUID.randomUUID()
            every { namespaceService.findById(unknownNs) } returns null
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, unknownNs.toString(), Action.WRITE)
            } returns true

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$unknownNs", "name": "Orphan", "content": ["Hello"] }"""),
            ).andExpect(status().isNotFound)
        }

        "POST with dangling namespaceId returns 403 for non-member (no existence leak)" {
            val unknownNs = UUID.randomUUID()
            every { namespaceService.findById(unknownNs) } returns null

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$unknownNs", "name": "Orphan", "content": ["Hello"] }"""),
            ).andExpect(status().isForbidden)
        }

        // -------------------------------------------------------------------------
        // POST — service-level validation
        // -------------------------------------------------------------------------

        "POST with blank content element returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Bad-${UUID.randomUUID()}", "content": ["Hello", "   "] }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST with duplicate parameter names returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "Dup-${UUID.randomUUID()}",
                            "content": ["Hello"],
                            "parameters": [
                                { "name": "lang", "defaultValue": "English" },
                                { "name": "lang", "defaultValue": "French" }
                            ]
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        "POST duplicate (namespaceId, name) returns 409" {
            val name = "DUP-${UUID.randomUUID()}"
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$name", "content": ["Hello"] }"""),
            ).andExpect(status().isCreated)

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$name", "content": ["Hello again"] }"""),
            ).andExpect(status().isConflict)
        }

        // -------------------------------------------------------------------------
        // PUT — Bean Validation
        // -------------------------------------------------------------------------

        "PUT with blank name returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/prompts/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "name": "", "content": ["Hello"] }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT with empty content list returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/prompts/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "name": "My Prompt", "content": [] }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT with blank content element returns 400" {
            val created = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "BLANK-CONTENT-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.PROMPT, created.id.toString(), Action.WRITE)
            } returns true

            mockMvc.perform(
                put("/api/prompts/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "name": "${created.name}", "content": ["Hello", "   "] }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT with valid payload returns 200" {
            val created = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "PUT-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.PROMPT, created.id.toString(), Action.WRITE)
            } returns true

            mockMvc.perform(
                put("/api/prompts/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "name": "${created.name}", "content": ["Updated"] }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0]").value("Updated"))
        }

        // -------------------------------------------------------------------------
        // DELETE
        // -------------------------------------------------------------------------

        "DELETE on existing prompt returns 204" {
            val created = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "DEL-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.PROMPT, created.id.toString(), Action.DELETE)
            } returns true

            mockMvc.perform(delete("/api/prompts/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // GET
        // -------------------------------------------------------------------------

        "GET non-existent id returns 404" {
            mockMvc.perform(get("/api/prompts/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        "GET by-parentId returns prompts for the namespace" {
            val name = "LIST-${UUID.randomUUID()}"
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = name,
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts/by-parentId/$namespaceId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }
    }
}
