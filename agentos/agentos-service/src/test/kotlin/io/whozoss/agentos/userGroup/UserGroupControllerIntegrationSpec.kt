package io.whozoss.agentos.userGroup

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.context.annotation.Import
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserGroupControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var userGroupService: UserGroupService

    @Autowired lateinit var namespaceService: NamespaceService

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    init {

        val aliceId = UUID.randomUUID()
        val alice = User(
            metadata = EntityMetadata(id = aliceId),
            externalId = "alice-ext",
            email = "alice@whoz.io",
            isAdmin = false,
        )

        beforeEach {
            every { userService.getCurrentUser() } returns alice
            // Default deny — individual tests grant the permission they need.
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
        }

        "GET /api/user-groups without namespaceExternalId returns 400" {
            mockMvc
                .perform(get("/api/user-groups"))
                .andExpect(status().isBadRequest)
        }

        "GET /api/user-groups?namespaceExternalId=unknown returns 404 (namespace not visible)" {
            // Caller is authenticated but the namespace does not exist; the guard returns false
            // and the controller throws AccessDeniedException → 404 (HideOnAccessDenied).
            mockMvc
                .perform(get("/api/user-groups").param("namespaceExternalId", "unknown-external-id"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // POST /api/user-groups — create
        // -------------------------------------------------------------------------

        "POST /api/user-groups without namespaceExternalId returns 400" {
            mockMvc
                .perform(
                    post("/api/user-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "Group A" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups without WRITE on namespace returns 404" {
            mockMvc
                .perform(
                    post("/api/user-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceExternalId": "ext-no-perm", "name": "Group A" }"""),
                ).andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // GET /api/user-groups — happy path with READ permission granted
        // -------------------------------------------------------------------------

        "GET /api/user-groups?namespaceExternalId= returns groups for the matching namespace when READ is granted" {
            val externalId = "federation-${UUID.randomUUID()}"
            val namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "test-namespace",
                        externalId = externalId,
                    ),
                )
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Group A",
                ),
            )
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Group B",
                ),
            )

            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespace.id.toString(), Action.READ)
            } returns true

            mockMvc
                .perform(get("/api/user-groups").param("namespaceExternalId", externalId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(2)))
        }
    }
}
