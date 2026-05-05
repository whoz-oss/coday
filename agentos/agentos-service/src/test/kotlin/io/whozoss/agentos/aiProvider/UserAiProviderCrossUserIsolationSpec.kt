package io.whozoss.agentos.aiProvider

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Cross-user isolation matrix for [UserAiProviderController] (NFR-SEC-1, AR19).
 *
 * For each (verb × mode) pair where `verb ∈ {GET, PUT, DELETE}` and
 * `mode ∈ {user-global, user × namespace}`, alice attempts to access bob's resource.
 * **Every cell must return 404** — never 403 — because 403 leaks existence.
 *
 * Plus two LIST scenarios: alice never sees bob's rows in either mode,
 * even when she explicitly supplies `?userId=bob.id` (mass-assignment guard FR20).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAiProviderCrossUserIsolationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
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
    private val bob = User(
        metadata = EntityMetadata(id = bobId),
        externalId = "bob@example.com",
        email = "bob@example.com",
        isAdmin = false,
    )
    private val sharedNamespaceId = UUID.randomUUID()

    init {
        beforeEach {
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ)
            } returns true
            every {
                permissionService.hasPermission(bobId.toString(), EntityType.NAMESPACE, sharedNamespaceId.toString(), Action.READ)
            } returns true
        }

        fun createBobProvider(namespaceId: UUID?, name: String): AiProvider =
            aiProviderService.create(
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    userId = bobId,
                    name = name,
                    apiType = AiApiType.Anthropic,
                    apiKey = "sk-ant-bobkey1234567890",
                ),
            )

        // -----------------------------------------------------------------
        // GET — both modes
        // -----------------------------------------------------------------
        "GET alice → bob.user-global returns 404 not 403" {
            val bobProvider = createBobProvider(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-ai-providers/${bobProvider.id}"))
                .andExpect(status().isNotFound)
        }

        "GET alice → bob.user-namespace returns 404 not 403" {
            val bobProvider = createBobProvider(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-ai-providers/${bobProvider.id}"))
                .andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // PUT — both modes
        // -----------------------------------------------------------------
        "PUT alice → bob.user-global returns 404 not 403" {
            val bobProvider = createBobProvider(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/user-ai-providers/${bobProvider.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "ATTACK", "apiType": "Anthropic" }"""),
            ).andExpect(status().isNotFound)
        }

        "PUT alice → bob.user-namespace returns 404 not 403" {
            val bobProvider = createBobProvider(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(
                put("/api/user-ai-providers/${bobProvider.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "ATTACK", "apiType": "Anthropic" }"""),
            ).andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // DELETE — both modes
        // -----------------------------------------------------------------
        "DELETE alice → bob.user-global returns 404 not 403" {
            val bobProvider = createBobProvider(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/user-ai-providers/${bobProvider.id}"))
                .andExpect(status().isNotFound)
        }

        "DELETE alice → bob.user-namespace returns 404 not 403" {
            val bobProvider = createBobProvider(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(delete("/api/user-ai-providers/${bobProvider.id}"))
                .andExpect(status().isNotFound)
        }

        // -----------------------------------------------------------------
        // LIST — alice never sees bob's rows
        // -----------------------------------------------------------------
        "LIST as alice never includes bob's rows in either mode" {
            createBobProvider(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            createBobProvider(namespaceId = sharedNamespaceId, name = "BOB_NS_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-ai-providers?size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[?(@.userId != '$aliceId')]").isEmpty)
        }

        "LIST as alice with ?userId=bob still filters on alice" {
            createBobProvider(namespaceId = null, name = "BOB_GLOBAL_${UUID.randomUUID()}")
            every { userService.getCurrentUser() } returns alice

            mockMvc.perform(get("/api/user-ai-providers?userId=$bobId&size=100"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[?(@.userId != '$aliceId')]").isEmpty)
        }
    }
}
