package io.whozoss.agentos.authSetting

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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Permission-integration test for [AuthSettingController] (#1773).
 *
 * Exercises the real permission chain on the embedded Neo4j harness:
 *   HTTP request -> AgentOsPermissionEvaluator -> PermissionServiceImpl -> Neo4jPermissionRepository -> Neo4j
 *
 * Before #1773, EntityType.AUTH_SETTING was missing from isNamespaceChildEntity and
 * PLATFORM_SCOPABLE_ENTITY_TYPES: every guarded endpoint answered 404 on a namespace-shared or
 * platform setting for anyone but a super-admin, `/connection` included.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AuthSettingControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var authSettingService: AuthSettingService

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
            alice = newUser("alice", isAdmin = false)
            bob = newUser("bob", isAdmin = false)
            admin = newUser("admin", isAdmin = true)
            listOf(alice, bob, admin).forEach { user ->
                userRepository.save(user)
                every { userService.findById(user.id) } returns user
            }
            every { userService.getCurrentUser() } returns alice

            namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        externalId = "test-ns-${UUID.randomUUID()}",
                        name = "Test Namespace",
                    ),
                )
        }

        // ---------------------------------------------------------------------
        // Namespace-shared setting
        // ---------------------------------------------------------------------

        "namespace MEMBER reads a namespace-shared setting and its connection state" {
            grant(alice, PermissionRelation.MEMBER)
            val setting = createOAuth(namespaceId = namespace.id)

            mockMvc.perform(get("/api/auth-settings/${setting.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(setting.id.toString()))
            mockMvc.perform(get("/api/auth-settings/${setting.id}/connection"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.connected").value(false))
        }

        "namespace MEMBER cannot update nor delete a namespace-shared setting" {
            grant(alice, PermissionRelation.MEMBER)
            val setting = createApiKey(namespaceId = namespace.id)

            putApiKey(setting).andExpect(status().isNotFound)
            mockMvc.perform(delete("/api/auth-settings/${setting.id}"))
                .andExpect(status().isNotFound)
        }

        "namespace ADMIN updates and deletes a namespace-shared setting" {
            grant(alice, PermissionRelation.ADMIN)
            val setting = createApiKey(namespaceId = namespace.id)

            putApiKey(setting)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.description").value("updated"))
            mockMvc.perform(delete("/api/auth-settings/${setting.id}"))
                .andExpect(status().isNoContent)
        }

        "a user without any relation to the namespace gets 404 on a namespace-shared setting" {
            val setting = createOAuth(namespaceId = namespace.id)

            mockMvc.perform(get("/api/auth-settings/${setting.id}"))
                .andExpect(status().isNotFound)
            mockMvc.perform(get("/api/auth-settings/${setting.id}/connection"))
                .andExpect(status().isNotFound)
        }

        // ---------------------------------------------------------------------
        // Platform setting
        // ---------------------------------------------------------------------

        "any authenticated user reads a platform setting and its connection state, but cannot change it" {
            val setting = createOAuth(namespaceId = null)

            mockMvc.perform(get("/api/auth-settings/${setting.id}"))
                .andExpect(status().isOk)
            mockMvc.perform(get("/api/auth-settings/${setting.id}/connection"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.connected").value(false))
            mockMvc.perform(delete("/api/auth-settings/${setting.id}"))
                .andExpect(status().isNotFound)
        }

        "a super-admin updates a platform setting" {
            every { userService.getCurrentUser() } returns admin
            val setting = createApiKey(namespaceId = null)

            putApiKey(setting).andExpect(status().isOk)
        }

        // ---------------------------------------------------------------------
        // User-global setting: ownership, never read as a platform row
        // ---------------------------------------------------------------------

        "the owner updates their user-global setting, another user gets 404" {
            val setting = createApiKey(namespaceId = null, userId = alice.id)

            putApiKey(setting).andExpect(status().isOk)

            every { userService.getCurrentUser() } returns bob
            mockMvc.perform(get("/api/auth-settings/${setting.id}"))
                .andExpect(status().isNotFound)
        }
    }

    private fun newUser(
        prefix: String,
        isAdmin: Boolean,
    ): User {
        val id = UUID.randomUUID()
        return User(
            metadata = EntityMetadata(id = id),
            externalId = "$prefix-$id@example.com",
            email = "$prefix-$id@example.com",
            isAdmin = isAdmin,
        )
    }

    private fun grant(
        user: User,
        relation: PermissionRelation,
    ) = permissionService.grantPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), relation)

    private fun createApiKey(
        namespaceId: UUID?,
        userId: UUID? = null,
    ): AuthSetting =
        authSettingService.create(
            ApiKeyAuthSetting(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                userId = userId,
                name = "KEY_${UUID.randomUUID()}",
                apiKey = "sk-test",
            ),
        )

    private fun createOAuth(namespaceId: UUID?): AuthSetting =
        authSettingService.create(
            OAuthRegisteredAuthSetting(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = "OAUTH_${UUID.randomUUID()}",
                clientId = "client",
                clientSecret = "secret",
                authorizationUrl = "https://idp.example.com/authorize",
                tokenUrl = "https://idp.example.com/token",
            ),
        )

    /** PUT as whoever `userService.getCurrentUser()` currently returns. */
    private fun putApiKey(setting: AuthSetting): ResultActions =
        mockMvc.perform(
            put("/api/auth-settings/${setting.id}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                        "id": "${setting.id}",
                        "authType": "API_KEY",
                        "name": "${setting.name}",
                        "description": "updated"
                    }
                    """.trimIndent(),
                ),
        )
}
