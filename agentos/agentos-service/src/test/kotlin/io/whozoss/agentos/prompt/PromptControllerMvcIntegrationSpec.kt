package io.whozoss.agentos.prompt

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
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
 * Verifies Bean Validation activation on POST and PUT, scope dispatch on POST,
 * service-level validation (duplicate parameter names), and CRUD happy paths.
 *
 * [PermissionService] is mocked to keep this spec focused on the HTTP/validation layer.
 * Permission scenarios (membership, ADMIN/MEMBER, platform scope, cross-user isolation)
 * are covered by [PromptControllerPermissionIntegrationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class PromptControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var promptService: PromptService
    @Autowired lateinit var agentConfigService: AgentConfigService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService
    @MockkBean(relaxed = true) lateinit var namespaceService: NamespaceService

    // IDs are declared as lateinit vars and re-initialized in beforeEach with fresh UUIDs
    // to avoid Neo4j unique-constraint conflicts across test runs on the shared embedded instance.
    private lateinit var aliceId: UUID
    private lateinit var alice: User
    private lateinit var namespaceId: UUID
    private lateinit var ns: Namespace

    init {
        beforeEach {
            aliceId = UUID.randomUUID()
            alice = User(
                metadata = EntityMetadata(id = aliceId),
                externalId = "alice-${aliceId}@example.com",
                email = "alice-${aliceId}@example.com",
                isAdmin = false,
            )
            namespaceId = UUID.randomUUID()
            ns = Namespace(
                metadata = EntityMetadata(id = namespaceId),
                externalId = "ns-$namespaceId",
                name = "ns",
            )

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

        "POST with blank content element returns 400" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Bad-${UUID.randomUUID()}", "content": ["Hello", "   "] }"""),
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
        // POST — service-level validation
        // -------------------------------------------------------------------------

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

        "POST namespace prompt returns 201" {
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "NS-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("\$.namespaceId").value(namespaceId.toString()))
        }

        "POST with userId not matching authenticated user returns 400" {
            val otherId = UUID.randomUUID()
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "userId": "$otherId", "name": "Bad-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // POST — agentConfigId validation
        // -------------------------------------------------------------------------

        "POST with non-existent agentConfigId returns 404" {
            val unknownId = UUID.randomUUID()
            val name = "AC-MISSING-" + UUID.randomUUID()
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "$name",
                            "content": ["Hello"],
                            "agentConfigId": "$unknownId"
                        }
                    """.trimIndent()),
            ).andExpect(status().isNotFound)
        }

        "POST with existing agentConfigId returns 201" {
            val agent = agentConfigService.create(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = "agent-" + UUID.randomUUID(),
                ),
            )
            val name = "AC-OK-" + UUID.randomUUID()
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "$name",
                            "content": ["Hello"],
                            "agentConfigId": "${agent.id}"
                        }
                    """.trimIndent()),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("\$.agentConfigId").value(agent.id.toString()))
        }

        "POST with agentConfigId from different namespace returns 400" {
            val otherNs = UUID.randomUUID()
            val agent = agentConfigService.create(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = otherNs,
                    name = "foreign-agent-" + UUID.randomUUID(),
                ),
            )
            val name = "AC-CROSS-NS-" + UUID.randomUUID()
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "$name",
                            "content": ["Hello"],
                            "agentConfigId": "${agent.id}"
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
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

        "POST :search returns prompts for the namespace" {
            val name = "LIST-${UUID.randomUUID()}"
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = name,
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(
                post("/api/prompts/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId" }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        // -------------------------------------------------------------------------
        // POST :search — namespaceExternalId resolution
        // -------------------------------------------------------------------------

        "POST :search with namespaceExternalId resolves to namespace and returns 200" {
            every { namespaceService.findByExternalId(ns.externalId!!) } returns ns

            val name = "EXT-${UUID.randomUUID()}"
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    name = name,
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(
                post("/api/prompts/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceExternalId": "${ns.externalId}" }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        "POST :search with unknown namespaceExternalId returns 404" {
            every { namespaceService.findByExternalId("unknown-ext-id") } returns null

            mockMvc.perform(
                post("/api/prompts/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceExternalId": "unknown-ext-id" }"""),
            ).andExpect(status().isNotFound)
        }

        "POST :search with both namespaceId and namespaceExternalId returns 400" {
            mockMvc.perform(
                post("/api/prompts/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "namespaceExternalId": "${ns.externalId}" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // POST :effective — externalId resolution (MVC/validation layer)
        // -------------------------------------------------------------------------

        "POST :effective with both namespaceId and namespaceExternalId returns 400" {
            mockMvc.perform(
                post("/api/prompts/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "namespaceExternalId": "${ns.externalId}",
                            "userId": "${aliceId}"
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        "POST :effective with both userId and userExternalId returns 400" {
            every { namespaceService.findByExternalId(ns.externalId!!) } returns ns

            mockMvc.perform(
                post("/api/prompts/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "userId": "${aliceId}",
                            "userExternalId": "alice@example.com"
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        "POST :effective with neither namespaceId nor namespaceExternalId returns 400" {
            mockMvc.perform(
                post("/api/prompts/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "userId": "${aliceId}" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST :effective with neither userId nor userExternalId returns 400" {
            mockMvc.perform(
                post("/api/prompts/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST :effective with unknown namespaceExternalId returns 404" {
            every { namespaceService.findByExternalId("no-such-ns") } returns null

            mockMvc.perform(
                post("/api/prompts/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceExternalId": "no-such-ns", "userId": "${aliceId}" }"""),
            ).andExpect(status().isNotFound)
        }

        "POST :effective with unknown userExternalId returns 404" {
            every { namespaceService.findByExternalId(ns.externalId!!) } returns ns
            every { userService.findByExternalId("no-such-user") } returns null

            mockMvc.perform(
                post("/api/prompts/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userExternalId": "no-such-user" }"""),
            ).andExpect(status().isNotFound)
        }
    }
}
