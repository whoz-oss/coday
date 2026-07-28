package io.whozoss.agentos.scheduledPrompt

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/**
 * Permission-integration test for [ScheduledPromptController].
 *
 * Uses the **real** [PermissionService] (backed by the embedded Neo4j harness) so that
 * the full permission evaluation chain is exercised:
 *   HTTP request → [AgentOsAuthenticationFilter] → [AgentOsPermissionEvaluator]
 *   → [PermissionServiceImpl] → [Neo4jPermissionRepository] → Neo4j
 *
 * This test specifically guards against regressions where [EntityType.SCHEDULED_PROMPT]
 * is missing from [Neo4jPermissionRepository.isNamespaceChildEntity], which would cause
 * [PermissionServiceImpl.hasTransitivePermission] to short-circuit to false for any
 * non-super-admin user, making PUT/PATCH/DELETE always return 404 for namespace-scoped
 * scheduled prompts even when the caller is ADMIN on the parent namespace.
 *
 * **Why [UserService] is still mocked**: identity resolution ([UserService.getCurrentUser])
 * is request-scoped and cannot be driven by MockMvc headers without a full security filter
 * rewrite. Mocking it lets us precisely control which user is "current" for each test
 * without touching the permission graph. [UserService.findById] is also mocked so
 * [PermissionServiceImpl] can check `user.isAdmin` from the mock.
 *
 * **Why [UserRepository] is autowired**: the Cypher queries in [PermissionNodeNeo4jRepository]
 * all start with `MATCH (u:User {id: \$userId})`. If no `(:User)` node exists in the graph,
 * permission grants are silently ignored and all permission checks return false.
 * [userRepository.save] writes the real `(:User)` node into Neo4j so that
 * `grantPermission` / `hasPermission` Cypher traversals can find the user.
 *
 * **Why [AgentConfigRepository] is autowired**: [ScheduledPromptService.createWithPrompt]
 * validates that the referenced [AgentConfig] exists. We create a real `(:AgentConfig)` node
 * in Neo4j so that the service can find it.
 *
 * Test strategy:
 * - Write real `(:User)` and `(:AgentConfig)` nodes into Neo4j before each test.
 * - Create real namespaces via the service layer.
 * - Grant / revoke permissions via [PermissionService.grantPermission].
 * - Assert HTTP status codes that prove the full evaluator path is working.
 *
 * Contrast with [ScheduledPromptControllerUnitSpec], which mocks [PermissionService]
 * and focuses on controller logic and DTO mapping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ScheduledPromptControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var scheduledPromptService: ScheduledPromptService
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository

    @MockkBean(relaxed = true) lateinit var userService: io.whozoss.agentos.user.UserService

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private lateinit var alice: User    // non-admin, will be granted ADMIN on namespace
    private lateinit var bob: User      // non-admin, no permissions
    private lateinit var admin: User    // super-admin (isAdmin = true)
    private lateinit var namespace: Namespace
    private lateinit var agentConfig: AgentConfig

    init {
        beforeEach {
            val aliceId = UUID.randomUUID()
            alice = User(
                metadata = EntityMetadata(id = aliceId),
                externalId = "alice-${aliceId}@example.com",
                email = "alice-${aliceId}@example.com",
                isAdmin = false,
            )

            val bobId = UUID.randomUUID()
            bob = User(
                metadata = EntityMetadata(id = bobId),
                externalId = "bob-${bobId}@example.com",
                email = "bob-${bobId}@example.com",
                isAdmin = false,
            )

            val adminId = UUID.randomUUID()
            admin = User(
                metadata = EntityMetadata(id = adminId),
                externalId = "admin-${adminId}@example.com",
                email = "admin-${adminId}@example.com",
                isAdmin = true,
            )

            // Persist real (:User) nodes so Cypher permission queries can MATCH them.
            userRepository.save(alice)
            userRepository.save(bob)
            userRepository.save(admin)

            every { userService.getCurrentUser() } returns alice
            every { userService.findById(aliceId) } returns alice
            every { userService.findById(bobId) } returns bob
            every { userService.findById(adminId) } returns admin

            // Create a real namespace.
            namespace = namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "test-ns-${UUID.randomUUID()}",
                    name = "Test Namespace",
                ),
            )

            // Create a real AgentConfig in the namespace so ScheduledPromptService can find it.
            agentConfig = agentConfigRepository.save(
                AgentConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "test-agent-${UUID.randomUUID()}",
                    enabled = true,
                ),
            )
        }

        // -------------------------------------------------------------------------
        // Helper: valid create payload
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // POST (create) — namespace ADMIN can create
        // -------------------------------------------------------------------------

        "POST returns 201 for namespace ADMIN (WRITE on namespace)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            mockMvc.perform(
                post("/api/scheduled-prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createPayload(namespace.id, agentConfig.id)),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").value(namespace.id.toString()))
        }

        "POST returns 403 for namespace MEMBER (READ only, WRITE required for namespace-scoped create)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            mockMvc.perform(
                post("/api/scheduled-prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createPayload(namespace.id, agentConfig.id)),
            ).andExpect(status().isForbidden)
        }

        "POST returns 403 for user with no namespace permission" {
            // alice has no permission on the namespace
            mockMvc.perform(
                post("/api/scheduled-prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createPayload(namespace.id, agentConfig.id)),
            ).andExpect(status().isForbidden)
        }

        // -------------------------------------------------------------------------
        // PUT (update) — @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'WRITE')")
        //
        // This is the key regression guard: without SCHEDULED_PROMPT in
        // isNamespaceChildEntity, hasTransitivePermission short-circuits to false and
        // namespace ADMIN always gets 404 here.
        // -------------------------------------------------------------------------

        "PUT returns 200 for namespace ADMIN (transitive WRITE via Namespace.ADMIN)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val sp = createScheduledPrompt()

            mockMvc.perform(
                put("/api/scheduled-prompts/${sp.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updatePayload(namespace.id, agentConfig.id, "updated name")),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("updated name"))
        }

        "PUT returns 404 for namespace MEMBER (READ only, WRITE denied — hidden via @HideOnAccessDenied)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val sp = createScheduledPrompt()

            mockMvc.perform(
                put("/api/scheduled-prompts/${sp.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updatePayload(namespace.id, agentConfig.id, "blocked update")),
            ).andExpect(status().isNotFound)
        }

        "PUT returns 404 for user with no namespace permission" {
            val sp = createScheduledPrompt()

            mockMvc.perform(
                put("/api/scheduled-prompts/${sp.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updatePayload(namespace.id, agentConfig.id, "blocked update")),
            ).andExpect(status().isNotFound)
        }

        "PUT returns 200 for super-admin regardless of explicit namespace membership" {
            every { userService.getCurrentUser() } returns admin

            val sp = createScheduledPrompt()

            mockMvc.perform(
                put("/api/scheduled-prompts/${sp.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updatePayload(namespace.id, agentConfig.id, "admin update")),
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // PATCH /toggle — @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'WRITE')")
        // -------------------------------------------------------------------------

        "PATCH /toggle returns 200 for namespace ADMIN (transitive WRITE via Namespace.ADMIN)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val sp = createScheduledPrompt()

            mockMvc.perform(patch("/api/scheduled-prompts/${sp.id}/toggle"))
                .andExpect(status().isOk)
        }

        "PATCH /toggle returns 404 for namespace MEMBER (WRITE denied — hidden via @HideOnAccessDenied)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val sp = createScheduledPrompt()

            mockMvc.perform(patch("/api/scheduled-prompts/${sp.id}/toggle"))
                .andExpect(status().isNotFound)
        }

        "PATCH /toggle returns 404 for user with no namespace permission" {
            val sp = createScheduledPrompt()

            mockMvc.perform(patch("/api/scheduled-prompts/${sp.id}/toggle"))
                .andExpect(status().isNotFound)
        }

        "PATCH /toggle returns 200 for super-admin" {
            every { userService.getCurrentUser() } returns admin

            val sp = createScheduledPrompt()

            mockMvc.perform(patch("/api/scheduled-prompts/${sp.id}/toggle"))
                .andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // DELETE — @PreAuthorize("hasPermission(#id, 'ScheduledPrompt', 'DELETE')")
        // -------------------------------------------------------------------------

        "DELETE returns 204 for namespace ADMIN (transitive DELETE via Namespace.ADMIN)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val sp = createScheduledPrompt()

            mockMvc.perform(delete("/api/scheduled-prompts/${sp.id}"))
                .andExpect(status().isNoContent)
        }

        "DELETE returns 404 for namespace MEMBER (DELETE requires ADMIN relation — hidden via @HideOnAccessDenied)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val sp = createScheduledPrompt()

            mockMvc.perform(delete("/api/scheduled-prompts/${sp.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE returns 404 for user with no namespace permission" {
            val sp = createScheduledPrompt()

            mockMvc.perform(delete("/api/scheduled-prompts/${sp.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE returns 204 for super-admin without explicit namespace membership" {
            every { userService.getCurrentUser() } returns admin

            val sp = createScheduledPrompt()

            mockMvc.perform(delete("/api/scheduled-prompts/${sp.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // Cross-user isolation: bob cannot mutate alice's namespace resources
        // -------------------------------------------------------------------------

        "bob cannot update a scheduled prompt even when alice has ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val sp = createScheduledPrompt()

            // Switch to bob — no permission on the namespace
            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(
                put("/api/scheduled-prompts/${sp.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updatePayload(namespace.id, agentConfig.id, "bob update")),
            ).andExpect(status().isNotFound)
        }

        "bob cannot delete a scheduled prompt even when alice has ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val sp = createScheduledPrompt()

            // Switch to bob
            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(delete("/api/scheduled-prompts/${sp.id}"))
                .andExpect(status().isNotFound)
        }
    }

    // -------------------------------------------------------------------------
    // Payload builders
    // -------------------------------------------------------------------------

    private fun createPayload(namespaceId: UUID, agentConfigId: UUID, name: String = "sp-${UUID.randomUUID()}"): String =
        """
        {
            "namespaceId": "$namespaceId",
            "agentConfigId": "$agentConfigId",
            "promptContent": "Hello, run the daily report",
            "name": "$name",
            "recurrence": {
                "unit": "WEEK",
                "days": [],
                "timeUtc": "08:00"
            },
            "planning": {
                "startDate": "${LocalDate.now()}",
                "endType": "NEVER"
            },
            "enabled": true
        }
        """.trimIndent()

    private fun updatePayload(namespaceId: UUID, agentConfigId: UUID, name: String): String =
        """
        {
            "namespaceId": "$namespaceId",
            "agentConfigId": "$agentConfigId",
            "promptContent": "Updated prompt content",
            "name": "$name",
            "recurrence": {
                "unit": "WEEK",
                "days": [],
                "timeUtc": "09:00"
            },
            "planning": {
                "startDate": "${LocalDate.now()}",
                "endType": "NEVER"
            },
            "enabled": true
        }
        """.trimIndent()

    /**
     * Creates a namespace-scoped scheduled prompt as alice (who must have ADMIN or
     * the caller switches to admin for the create then back to alice).
     *
     * Creates via the service layer directly to bypass HTTP-level auth on create
     * and focus the test assertions on the update/toggle/delete paths.
     */
    private fun createScheduledPrompt(name: String = "sp-${UUID.randomUUID()}"): ScheduledPrompt {
        // Grant admin temporarily for creation if alice has no permission yet.
        // We use the service directly to avoid HTTP auth complications on POST.
        val (sp, _) = scheduledPromptService.createWithPrompt(
            ScheduledPrompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespace.id,
                userId = null,
                agentConfigId = agentConfig.id,
                promptTemplateId = UUID.randomUUID(), // placeholder, overwritten by service
                name = name,
                recurrence = Recurrence(
                    unit = io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit.WEEK,
                    timeUtc = java.time.LocalTime.of(8, 0),
                ),
                planning = Planning(
                    startDate = LocalDate.now(),
                    endType = io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType.NEVER,
                ),
                enabled = true,
                nextRunAt = java.time.Instant.EPOCH,
            ),
            "Hello, run the daily report",
        )
        return sp
    }
}
