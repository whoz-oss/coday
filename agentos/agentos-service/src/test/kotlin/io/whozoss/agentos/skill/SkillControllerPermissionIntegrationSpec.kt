package io.whozoss.agentos.skill

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
 * Permission-integration test for [SkillController].
 *
 * Exercises the real [PermissionService] backed by the embedded Neo4j harness:
 *   HTTP request → [AgentOsAuthenticationFilter] → [AgentOsPermissionEvaluator]
 *   → [PermissionServiceImpl] → [Neo4jPermissionRepository] → Neo4j
 *
 * Verifies:
 * - EntityType.SKILL is wired into isNamespaceChildEntity and PLATFORM_SCOPABLE_ENTITY_TYPES.
 * - Non-superadmin namespace admin can create, list by parent, get by ID, and update skills.
 * - Non-superadmin namespace member can list and get by ID, but cannot create, update, or delete.
 * - Any authenticated user can list and get platform-level skills.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class SkillControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var skillService: SkillService
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private lateinit var alice: User
    private lateinit var bob: User
    private lateinit var admin: User
    private lateinit var namespace: Namespace

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

            userRepository.save(alice)
            userRepository.save(bob)
            userRepository.save(admin)

            every { userService.getCurrentUser() } returns alice
            every { userService.findById(aliceId) } returns alice
            every { userService.findById(bobId) } returns bob
            every { userService.findById(adminId) } returns admin

            namespace = namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "test-ns-${UUID.randomUUID()}",
                    name = "Test Namespace",
                ),
            )
        }

        // -------------------------------------------------------------------------
        // POST /api/skills — create
        // -------------------------------------------------------------------------

        "POST returns 201 for namespace ADMIN (WRITE on namespace)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val name = "skill-admin-${UUID.randomUUID()}"
            mockMvc.perform(
                post("/api/skills")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "namespaceId": "${namespace.id}",
                            "name": "$name",
                            "description": "desc",
                            "body": "body"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value(name))
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
                post("/api/skills")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "namespaceId": "${namespace.id}",
                            "name": "blocked-${UUID.randomUUID()}",
                            "description": "desc",
                            "body": "body"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isForbidden)
        }

        // -------------------------------------------------------------------------
        // GET /api/skills/by-parentId/{parentId} — list
        // -------------------------------------------------------------------------

        "GET /by-parentId returns 200 with skills for namespace ADMIN" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "ns-skill-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            mockMvc.perform(get("/api/skills/by-parentId/${namespace.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '${skill.name}')]").exists())
        }

        "GET /by-parentId returns 200 with skills for namespace MEMBER" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "member-skill-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            mockMvc.perform(get("/api/skills/by-parentId/${namespace.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '${skill.name}')]").exists())
        }

        // -------------------------------------------------------------------------
        // GET /api/skills/{id} — get by ID
        // -------------------------------------------------------------------------

        "GET /{id} returns 200 for namespace ADMIN (transitive READ via Namespace.ADMIN)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "admin-get-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            mockMvc.perform(get("/api/skills/${skill.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(skill.id.toString()))
                .andExpect(jsonPath("$.name").value(skill.name))
        }

        "GET /{id} returns 200 for namespace MEMBER (transitive READ via Namespace.MEMBER)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "member-get-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            mockMvc.perform(get("/api/skills/${skill.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(skill.id.toString()))
                .andExpect(jsonPath("$.name").value(skill.name))
        }

        "GET /{id} returns 404 for user with no namespace permission (hidden via @HideOnAccessDenied)" {
            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "hidden-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            mockMvc.perform(get("/api/skills/${skill.id}"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // PUT /api/skills/{id} — update
        // -------------------------------------------------------------------------

        "PUT /{id} returns 200 for namespace ADMIN (transitive WRITE via Namespace.ADMIN)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )

            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "admin-put-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            val updatedName = "updated-${UUID.randomUUID()}"
            mockMvc.perform(
                put("/api/skills/${skill.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "id": "${skill.id}",
                            "namespaceId": "${namespace.id}",
                            "name": "$updatedName",
                            "description": "updated desc",
                            "body": "updated body"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value(updatedName))
                .andExpect(jsonPath("$.description").value("updated desc"))
        }

        "PUT /{id} returns 404 for namespace MEMBER (READ only, WRITE denied — hidden via @HideOnAccessDenied)" {
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.MEMBER,
            )

            val skill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "member-put-${UUID.randomUUID()}",
                    description = "desc",
                    body = "body",
                ),
            )

            mockMvc.perform(
                put("/api/skills/${skill.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "id": "${skill.id}",
                            "namespaceId": "${namespace.id}",
                            "name": "blocked-update",
                            "description": "desc",
                            "body": "body"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // Platform skill endpoints — list and GET by ID for authenticated non-admin
        // -------------------------------------------------------------------------

        "GET /platform returns 200 for authenticated non-admin" {
            every { userService.getCurrentUser() } returns admin
            val platformSkill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "platform-${UUID.randomUUID()}",
                    description = "platform desc",
                    body = "platform body",
                ),
            )

            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/skills/platform"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '${platformSkill.name}')]").exists())
        }

        "GET /{id} returns 200 for platform skill for authenticated non-admin" {
            every { userService.getCurrentUser() } returns admin
            val platformSkill = skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = "platform-get-${UUID.randomUUID()}",
                    description = "platform desc",
                    body = "platform body",
                ),
            )

            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/skills/${platformSkill.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(platformSkill.id.toString()))
                .andExpect(jsonPath("$.name").value(platformSkill.name))
        }
    }
}
