package io.whozoss.agentos.integrationConfig

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
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
 * MVC-layer test for the unified [IntegrationConfigController].
 *
 * Verifies Bean Validation activation, JSON deserialisation, the
 * @HideOnAccessDenied → 404 translation, and the implicit-scope dispatch on `POST`
 * (Decision 15) end-to-end inside a real Spring Boot context.
 *
 * Absorbs the legacy `UserIntegrationConfigControllerMvcTest` (Decision 19 + Task 5
 * of `tech-spec-unify-ns-user-crud-controllers.md`). Cross-user isolation is in
 * [IntegrationConfigCrossUserIsolationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntegrationConfigControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var integrationConfigService: IntegrationConfigService

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

        "POST /api/integration-configs without body returns 400" {
            mockMvc.perform(
                post("/api/integration-configs").contentType(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank name returns 400" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "", "integrationType": "JIRA" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank integrationType returns 400" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "JIRA_PROD", "integrationType": "" }"""),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // POST — Decision 15 implicit scope dispatch
        // -------------------------------------------------------------------------

        "POST with neither namespaceId nor userId returns 400 (must provide one or both)" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "JIRA_PROD", "integrationType": "JIRA" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST NS-shared (namespaceId only) returns 201" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "JIRA_PROD_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isCreated)
        }

        "POST with parameters returns 201" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "SLACK_DEV_${UUID.randomUUID()}", "integrationType": "SLACK", "parameters": { "webhookUrl": "https://hooks.slack.com/xxx" } }"""),
            ).andExpect(status().isCreated)
        }

        "POST user-global (userId=me) returns 201" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "userId": "$aliceId", "name": "GLOBAL_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
        }

        "POST user-namespace with READ permission returns 201" {
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userId": "$aliceId", "name": "NS_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
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
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$foreignNs", "userId": "$aliceId", "name": "FOREIGN_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isForbidden)
        }

        "POST with mismatched body.userId returns 400 (mass-assignment guard)" {
            val attackerUserId = UUID.randomUUID()
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "userId": "$attackerUserId", "name": "ATTACK_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST with dangling namespaceId returns 404" {
            val unknownNs = UUID.randomUUID()
            every { namespaceService.findById(unknownNs) } returns null

            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$unknownNs", "userId": "$aliceId", "name": "ORPHAN_${UUID.randomUUID()}", "integrationType": "JIRA" }"""),
            ).andExpect(status().isNotFound)
        }

        "POST duplicate triple returns 409" {
            val name = "DUP_${UUID.randomUUID()}"
            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userId": "$aliceId", "name": "$name", "integrationType": "JIRA" }"""),
            ).andExpect(status().isCreated)

            mockMvc.perform(
                post("/api/integration-configs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "userId": "$aliceId", "name": "$name", "integrationType": "JIRA" }"""),
            ).andExpect(status().isConflict)
        }

        // -------------------------------------------------------------------------
        // PUT — Bean Validation + immutable fields
        // -------------------------------------------------------------------------

        "PUT /api/integration-configs/{id} with blank name returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/integration-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "", "integrationType": "JIRA" }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/integration-configs/{id} with blank integrationType returns 400" {
            val id = UUID.randomUUID()
            mockMvc.perform(
                put("/api/integration-configs/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "JIRA_PROD", "integrationType": "" }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/integration-configs/{id} with valid payload returns 200" {
            // Owned by alice so the evaluator's ownership branch grants WRITE
            // (PermissionService is fully mocked, no entity-level membership grant).
            val created = integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "GITHUB_MAIN_${UUID.randomUUID()}",
                    integrationType = "GITHUB",
                ),
            )

            mockMvc.perform(
                put("/api/integration-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "name": "${created.name}", "integrationType": "GITHUB" }"""),
            ).andExpect(status().isOk)
        }

        "PUT preserves userId even when body sends another (mass-assignment guard)" {
            val created = integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "PUT_${UUID.randomUUID()}",
                    integrationType = "JIRA",
                ),
            )
            val attackerUserId = UUID.randomUUID()

            mockMvc.perform(
                put("/api/integration-configs/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "userId": "$attackerUserId", "name": "${created.name}", "integrationType": "JIRA", "description": "renamed" }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.description").value("renamed"))
        }

        // -------------------------------------------------------------------------
        // DELETE
        // -------------------------------------------------------------------------

        "DELETE on own row returns 204" {
            val created = integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "DEL_${UUID.randomUUID()}",
                    integrationType = "JIRA",
                ),
            )
            mockMvc.perform(delete("/api/integration-configs/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // GET / LIST
        // -------------------------------------------------------------------------

        "GET non-existent id returns 404 (not 403)" {
            mockMvc.perform(get("/api/integration-configs/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        "LIST returns paginated envelope for caller's configs" {
            val tag = "LIST_${UUID.randomUUID()}"
            integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = aliceId,
                    name = "$tag-A",
                    integrationType = "JIRA",
                ),
            )

            mockMvc.perform(get("/api/integration-configs?size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content").isArray)
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.page").value(0))
        }
    }
}
