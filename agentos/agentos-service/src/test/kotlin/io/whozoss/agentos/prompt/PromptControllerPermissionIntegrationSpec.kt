package io.whozoss.agentos.prompt

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Permission-integration test for [PromptController].
 *
 * Uses the **real** [PermissionService] (backed by the embedded Neo4j harness) so that
 * the full permission evaluation chain is exercised:
 *   HTTP request → [AgentOsAuthenticationFilter] → [AgentOsPermissionEvaluator]
 *   → [PermissionServiceImpl] → [Neo4jPermissionRepository] → Neo4j
 *
 * **Why [UserService] is still mocked**: identity resolution ([UserService.getCurrentUser])
 * is request-scoped and depends on OS username or HTTP headers that are not present in
 * [MockMvc] requests. Mocking it lets us precisely control which user is "current" for
 * each test without touching the permission graph. [UserService.findById] is also mocked
 * so [PermissionServiceImpl] can check `user.isAdmin` from the mock.
 *
 * **Why [UserRepository] is autowired**: the Cypher queries in [PermissionNodeNeo4jRepository]
 * all start with `MATCH (u:User {id: $userId})`. If no `(:User)` node exists in the graph,
 * permission grants are silently ignored and all permission checks return false.
 * [userRepository.save] writes the real `(:User)` node into Neo4j so that
 * `grantPermission` / `hasPermission` Cypher traversals can find the user.
 *
 * Test strategy:
 * - Write real `(:User)` nodes into Neo4j via [UserRepository] before granting permissions.
 * - Create real namespaces and prompts via the service layer.
 * - Grant / revoke permissions via [PermissionService.grantPermission] / [revokePermission].
 * - Assert HTTP status codes that prove the full evaluator path is working.
 *
 * Contrast with [PromptControllerMvcIntegrationSpec], which mocks [PermissionService]
 * and focuses on Bean Validation, service-level validation, and CRUD happy paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class PromptControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var promptService: PromptService
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService

    /**
     * UserRepository (real Neo4j-backed bean) used to persist `(:User)` nodes so that
     * Cypher permission queries can MATCH them. The mock UserService does not write to Neo4j.
     */
    @Autowired lateinit var userRepository: UserRepository

    // Only UserService is mocked — identity resolution is request-scoped and cannot be
    // driven by MockMvc headers without a full security filter rewrite.
    @MockkBean(relaxed = true) lateinit var userService: UserService

    // -------------------------------------------------------------------------
    // Test fixtures — created fresh per test via beforeEach
    // -------------------------------------------------------------------------

    private lateinit var alice: User
    private lateinit var bob: User
    private lateinit var admin: User
    private lateinit var namespace: Namespace

    init {
        beforeEach {
            // Create real user objects with random UUIDs so each test run is isolated.
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

            // Persist real (:User) nodes into Neo4j so that Cypher permission queries
            // (MATCH (u:User {id: $userId}) ...) can find them.
            userRepository.save(alice)
            userRepository.save(bob)
            userRepository.save(admin)

            // Configure mock UserService: getCurrentUser returns alice by default;
            // findById returns the matching user so PermissionServiceImpl can check isAdmin.
            every { userService.getCurrentUser() } returns alice
            every { userService.findById(aliceId) } returns alice
            every { userService.findById(bobId) } returns bob
            every { userService.findById(adminId) } returns admin

            // Create a real namespace in Neo4j.
            namespace = namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "test-ns-${UUID.randomUUID()}",
                    name = "Test Namespace",
                ),
            )
        }

        // -------------------------------------------------------------------------
        // GET /{id} — @PreAuthorize("hasPermission(#id, 'Prompt', 'READ')")
        // -------------------------------------------------------------------------

        "GET /{id} returns 404 when prompt does not exist" {
            mockMvc.perform(get("/api/prompts/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        "GET /{id} returns 200 for super-admin bypassing namespace membership" {
            every { userService.getCurrentUser() } returns admin

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Admin-readable-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(prompt.id.toString()))
        }

        "GET /{id} returns 404 for user without any namespace membership (access denied hidden as 404)" {
            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Hidden-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // alice has no permission on the namespace — evaluator returns false → @HideOnAccessDenied → 404
            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isNotFound)
        }

        "GET /{id} returns 200 for namespace MEMBER (transitive READ on Prompt)" {
            // Grant alice MEMBER on the namespace → transitive READ on all namespace-scoped Prompts
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Member-readable-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value(prompt.name))
        }

        "GET /{id} returns 200 for namespace ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Admin-ns-readable-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isOk)
        }

        "GET /{id} returns 404 after MEMBER permission is revoked" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Revoked-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // Confirm readable before revoke
            mockMvc.perform(get("/api/prompts/${prompt.id}")).andExpect(status().isOk)

            // Revoke and confirm access is gone
            permissionService.revokePermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isNotFound)
        }

        "GET /{id} returns 200 for platform-scoped prompt (namespaceId null) for any authenticated user" {
            // Platform prompts have namespaceId = null; PermissionServiceImpl grants READ
            // to any authenticated non-admin user for platform-scope entities (entityId = null path).
            every { userService.getCurrentUser() } returns admin
            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "Platform-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // Switch to alice (non-admin) — platform READ is open to all authenticated users
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // GET /by-parentId/{parentId} — @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
        // -------------------------------------------------------------------------

        "GET /by-parentId returns 403 when caller has no membership on the namespace" {
            mockMvc.perform(get("/api/prompts/by-parentId/${namespace.id}"))
                .andExpect(status().isForbidden)
        }

        "GET /by-parentId returns 200 with prompts for namespace MEMBER" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val name = "List-${UUID.randomUUID()}"
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = name,
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts/by-parentId/${namespace.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$[?(@.name == '$name')]").exists())
        }

        // -------------------------------------------------------------------------
        // GET /api/prompts (no params) — platform scope
        // -------------------------------------------------------------------------

        "GET /api/prompts returns 200 with platform prompts for any authenticated user" {
            every { userService.getCurrentUser() } returns admin
            val name = "Platform-list-${UUID.randomUUID()}"
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = name,
                    content = listOf("Hello"),
                ),
            )

            every { userService.getCurrentUser() } returns alice
            mockMvc.perform(get("/api/prompts"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$[?(@.name == '$name')]").exists())
        }

        "GET /api/prompts does not include namespace-scoped prompts" {
            every { userService.getCurrentUser() } returns admin
            val nsName = "NS-only-${UUID.randomUUID()}"
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = nsName,
                    content = listOf("Hello"),
                ),
            )

            every { userService.getCurrentUser() } returns alice
            mockMvc.perform(get("/api/prompts"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$nsName')]").doesNotExist())
        }

        // -------------------------------------------------------------------------
        // GET /api/prompts?userId=me — user-scoped listing
        // -------------------------------------------------------------------------

        "GET /api/prompts?userId=me returns 200 with user-scoped prompts" {
            // Create a user-global prompt for alice
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = "User-global-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts").param("userId", "me"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        "GET /api/prompts?namespaceId=none&userId=me returns only user-global prompts" {
            val userGlobalName = "UserGlobal-${UUID.randomUUID()}"
            val userNsName = "UserNs-${UUID.randomUUID()}"

            // user-global prompt (namespaceId = null, userId = alice)
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = userGlobalName,
                    content = listOf("Hello"),
                ),
            )
            // user × namespace prompt (namespaceId != null, userId = alice)
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    userId = alice.id,
                    name = userNsName,
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(get("/api/prompts").param("namespaceId", "none").param("userId", "me"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$userGlobalName')]").exists())
                .andExpect(jsonPath("$[?(@.name == '$userNsName')]").doesNotExist())
        }

        "GET /api/prompts?userId=me does not return prompts owned by another user" {
            // Create a user-global prompt for bob
            promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = bob.id,
                    name = "Bob-only-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // alice requests her own prompts — bob's prompt must not appear
            mockMvc.perform(get("/api/prompts").param("userId", "me"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name =~ /Bob-only-.*/i)]").doesNotExist())
        }

        "GET /by-parentId returns 200 for super-admin without explicit namespace membership" {
            every { userService.getCurrentUser() } returns admin

            mockMvc.perform(get("/api/prompts/by-parentId/${namespace.id}"))
                .andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // POST — @PreAuthorize("isAuthenticated()") + controller-level WRITE check
        // -------------------------------------------------------------------------

        "POST returns 403 when caller lacks WRITE on namespace" {
            // alice has no permission at all — real evaluator denies
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${namespace.id}", "name": "Blocked-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isForbidden)
        }

        "POST returns 201 when caller is namespace ADMIN (WRITE granted)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${namespace.id}", "name": "Created-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").value(namespace.id.toString()))
        }

        "POST namespace prompt returns 403 for MEMBER (READ only, not WRITE)" {
            // MEMBER grants READ but not WRITE — the controller checks WRITE explicitly
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${namespace.id}", "name": "MemberBlocked-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isForbidden)
        }

        "POST platform prompt returns 403 for non-admin" {
            // alice.isAdmin = false — real PermissionServiceImpl denies platform WRITE
            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "Platform-blocked-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isForbidden)
        }

        "POST platform prompt returns 201 for super-admin" {
            every { userService.getCurrentUser() } returns admin

            mockMvc.perform(
                post("/api/prompts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "Platform-admin-${UUID.randomUUID()}", "content": ["Hello"] }"""),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").doesNotExist())
        }

        // -------------------------------------------------------------------------
        // PUT /{id} — @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
        // -------------------------------------------------------------------------

        "PUT returns 404 for user without WRITE (access denied hidden as 404 via @HideOnAccessDenied)" {
            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "NoWrite-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(
                put("/api/prompts/${prompt.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${prompt.name}", "content": ["Updated"] }"""),
            ).andExpect(status().isNotFound)
        }

        "PUT returns 200 for namespace ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Writable-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(
                put("/api/prompts/${prompt.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${prompt.name}", "content": ["Updated content"] }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0]").value("Updated content"))
        }

        "PUT returns 404 for namespace MEMBER (READ only, WRITE denied)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "MemberWrite-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // MEMBER cannot WRITE — @HideOnAccessDenied maps 403 → 404
            mockMvc.perform(
                put("/api/prompts/${prompt.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${prompt.name}", "content": ["Updated"] }"""),
            ).andExpect(status().isNotFound)
        }

        "PUT returns 200 for super-admin regardless of explicit namespace membership" {
            every { userService.getCurrentUser() } returns admin

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Admin-write-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(
                put("/api/prompts/${prompt.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${prompt.name}", "content": ["Admin updated"] }"""),
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // DELETE /{id} — @PreAuthorize("hasPermission(#id, 'Prompt', 'DELETE')")
        // -------------------------------------------------------------------------

        "DELETE returns 404 for user without DELETE permission (hidden via @HideOnAccessDenied)" {
            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "NoDelete-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(delete("/api/prompts/${prompt.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE returns 204 for namespace ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Deletable-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(delete("/api/prompts/${prompt.id}"))
                .andExpect(status().isNoContent)
        }

        "DELETE returns 404 for namespace MEMBER (DELETE requires ADMIN relation)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "MemberDelete-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // MEMBER cannot DELETE — @HideOnAccessDenied maps 403 → 404
            mockMvc.perform(delete("/api/prompts/${prompt.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE returns 204 for super-admin without explicit namespace membership" {
            every { userService.getCurrentUser() } returns admin

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Admin-delete-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(delete("/api/prompts/${prompt.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // Cross-user isolation: bob cannot access alice's namespace resources
        // -------------------------------------------------------------------------

        "bob cannot read a prompt in a namespace where only alice has MEMBER" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Alice-only-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // Switch to bob — no permission on the namespace
            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isNotFound)
        }

        "bob cannot delete a prompt even when alice has ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Alice-admin-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            // Switch to bob
            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(delete("/api/prompts/${prompt.id}"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // Ownership branch — user-scoped prompts (userId = alice)
        // -------------------------------------------------------------------------

        "PUT returns 200 for the prompt owner (userId == caller)" {
            val userPrompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = "Owner-write-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(
                put("/api/prompts/${userPrompt.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${userPrompt.name}", "content": ["Updated by owner"] }"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0]").value("Updated by owner"))
        }

        "DELETE returns 204 for the prompt owner (userId == caller)" {
            val userPrompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = "Owner-delete-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            mockMvc.perform(delete("/api/prompts/${userPrompt.id}"))
                .andExpect(status().isNoContent)
        }

        "GET /{id} returns 404 for a non-owner on a user-global prompt (not platform-readable)" {
            // alice's user-global prompt (namespaceId=null, userId=alice) must NOT be
            // readable by bob — isPlatformScoped must return false because userId IS NOT NULL.
            val userPrompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = "User-global-not-platform-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(get("/api/prompts/${userPrompt.id}"))
                .andExpect(status().isNotFound)
        }

        "PUT returns 404 for a non-owner on a user-scoped prompt" {
            val userPrompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = "Non-owner-write-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(
                put("/api/prompts/${userPrompt.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "${userPrompt.name}", "content": ["Bob tries"] }"""),
            ).andExpect(status().isNotFound)
        }

        "DELETE returns 404 for a non-owner on a user-scoped prompt" {
            val userPrompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = alice.id,
                    name = "Non-owner-delete-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(delete("/api/prompts/${userPrompt.id}"))
                .andExpect(status().isNotFound)
        }

        "granting bob MEMBER on the namespace allows him to read the prompt" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )
            permissionService.grantPermission(
                bob.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val prompt = promptService.create(
                Prompt(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Shared-${UUID.randomUUID()}",
                    content = listOf("Hello"),
                ),
            )

            every { userService.getCurrentUser() } returns bob

            mockMvc.perform(get("/api/prompts/${prompt.id}"))
                .andExpect(status().isOk)
        }
    }
}
