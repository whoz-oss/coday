package io.whozoss.agentos.namespace

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.hamcrest.Matchers.contains
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Permission-integration test for `PATCH /api/namespaces/{id}/members` on [NamespaceMembershipController].
 *
 * Exercises the **real** chain (@PreAuthorize -> AgentOsPermissionEvaluator -> PermissionServiceImpl -> Neo4j)
 * down to [NamespacePermissionService.updateMembers] and its Neo4j writes, against the embedded harness.
 * [UserService] is mocked for the request-scoped identity only, its user lookups delegate to the real
 * [UserRepository] — see [io.whozoss.agentos.userGroup.UserGroupControllerPermissionIntegrationSpec] for
 * the same setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class NamespaceMembershipControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private lateinit var namespace: Namespace

    private fun saveUser(isAdmin: Boolean = false): User {
        val id = UUID.randomUUID()
        return userRepository.save(
            User(
                metadata = EntityMetadata(id = id),
                externalId = "user-$id@example.com",
                email = "user-$id@example.com",
                isAdmin = isAdmin,
            ),
        )
    }

    private fun actAs(caller: User) {
        every { userService.getCurrentUser() } returns caller
        every { userService.findById(caller.id) } returns caller
    }

    private fun grant(
        user: User,
        relation: PermissionRelation,
    ) = permissionService.grantPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), relation)

    private fun patchMembers(body: String): ResultActions =
        mockMvc.perform(
            patch("/api/namespaces/${namespace.id}/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )

    private fun roleChangeBody(
        user: User,
        relation: PermissionRelation,
    ) = """[{ "userId": "${user.id}", "role": "${relation.name}" }]"""

    private fun namespaceAdminIds(): List<String> =
        permissionService.listUsersWithPermission(
            EntityType.NAMESPACE,
            namespace.id.toString(),
            PermissionRelation.ADMIN,
        )

    init {
        beforeEach {
            every { userService.findByIds(any(), any()) } answers { userRepository.findByIds(firstArg(), secondArg()) }
            namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        externalId = "ns-${UUID.randomUUID()}",
                        name = "Members Namespace",
                    ),
                )
        }

        "PATCH /members lets a namespace ADMIN demote another ADMIN" {
            val caller = saveUser()
            val otherAdmin = saveUser()
            actAs(caller)
            grant(caller, PermissionRelation.ADMIN)
            grant(otherAdmin, PermissionRelation.ADMIN)

            patchMembers(roleChangeBody(otherAdmin, PermissionRelation.MEMBER))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.id == '${otherAdmin.id}')].role", contains("MEMBER")))

            namespaceAdminIds() shouldBe listOf(caller.id.toString())
        }

        "PATCH /members lets a super-admin without any namespace relation demote an ADMIN" {
            val superAdmin = saveUser(isAdmin = true)
            val keptAdmin = saveUser()
            val demotedAdmin = saveUser()
            actAs(superAdmin)
            grant(keptAdmin, PermissionRelation.ADMIN)
            grant(demotedAdmin, PermissionRelation.ADMIN)

            patchMembers(roleChangeBody(demotedAdmin, PermissionRelation.MEMBER))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.id == '${demotedAdmin.id}')].role", contains("MEMBER")))

            namespaceAdminIds() shouldBe listOf(keptAdmin.id.toString())
        }

        "PATCH /members returns 403 for a namespace MEMBER and leaves the roles unchanged" {
            val caller = saveUser()
            val admin = saveUser()
            actAs(caller)
            grant(caller, PermissionRelation.MEMBER)
            grant(admin, PermissionRelation.ADMIN)

            patchMembers(roleChangeBody(admin, PermissionRelation.MEMBER))
                .andExpect(status().isForbidden)

            namespaceAdminIds() shouldBe listOf(admin.id.toString())
        }

        "PATCH /members returns 400 for a null entry in the body" {
            val caller = saveUser()
            actAs(caller)
            grant(caller, PermissionRelation.ADMIN)

            patchMembers("[null]")
                .andExpect(status().isBadRequest)
        }
    }
}
