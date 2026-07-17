package io.whozoss.agentos.userGroup

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
import org.hamcrest.Matchers.contains
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Permission-integration test for [UserGroupController].
 *
 * Exercises the **real** permission chain (@PreAuthorize -> AgentOsPermissionEvaluator ->
 * PermissionServiceImpl -> Neo4j) against the embedded harness, so the READ/WRITE/DELETE gates on
 * the UserGroup endpoints are actually asserted — unlike [UserGroupControllerIntegrationSpec], whose
 * only caller is the auto-created super-admin and therefore never exercises a denied path.
 *
 * Callers are authorized transitively via namespace membership (namespace MEMBER -> group READ,
 * namespace ADMIN -> group WRITE/DELETE, resolved through the group's [:BELONGS_TO] edge). Granting at
 * the namespace level mirrors how the app grants group access and avoids a direct group edge that role
 * reconciliation would touch. See [io.whozoss.agentos.prompt.PromptControllerPermissionIntegrationSpec]
 * for why [UserService] is mocked (request-scoped identity) while [UserRepository] is real (permission
 * Cypher must MATCH a real (:User) node).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserGroupControllerPermissionIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userGroupService: UserGroupService
    @Autowired lateinit var userGroupRepository: UserGroupRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private lateinit var caller: User
    private lateinit var namespace: Namespace

    init {
        beforeEach {
            val callerId = UUID.randomUUID()
            caller =
                User(
                    metadata = EntityMetadata(id = callerId),
                    externalId = "caller-$callerId@example.com",
                    email = "caller-$callerId@example.com",
                    isAdmin = false,
                )
            userRepository.save(caller)
            every { userService.getCurrentUser() } returns caller
            every { userService.findById(callerId) } returns caller

            namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        externalId = "ns-${UUID.randomUUID()}",
                        name = "Test Namespace",
                    ),
                )
        }

        fun createGroup(name: String) =
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = name,
                ),
            )

        fun grantNamespace(relation: PermissionRelation) =
            permissionService.grantPermission(
                caller.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                relation,
            )

        // -------------------------------------------------------------------------
        // GET /{id}/members — @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'READ')")
        // -------------------------------------------------------------------------

        "GET /{id}/members returns 404 for a caller without READ (access denied hidden as 404)" {
            val group = createGroup("Members-hidden")
            mockMvc
                .perform(get("/api/user-groups/${group.id}/members"))
                .andExpect(status().isNotFound)
        }

        "GET /{id}/members returns 200 for a namespace MEMBER (transitive READ)" {
            val group = createGroup("Members-readable")
            grantNamespace(PermissionRelation.MEMBER)
            mockMvc
                .perform(get("/api/user-groups/${group.id}/members"))
                .andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // POST / — create — @PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'WRITE')")
        // -------------------------------------------------------------------------

        "POST returns 403 when caller lacks WRITE on the namespace" {
            mockMvc
                .perform(
                    post("/api/user-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "${namespace.id}", "name": "Blocked-${UUID.randomUUID()}" }"""),
                ).andExpect(status().isForbidden)
        }

        "POST returns 201 when caller is namespace ADMIN" {
            grantNamespace(PermissionRelation.ADMIN)
            mockMvc
                .perform(
                    post("/api/user-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "${namespace.id}", "name": "Created-${UUID.randomUUID()}" }"""),
                ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // POST /{id} — update — @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'WRITE')")
        // -------------------------------------------------------------------------

        "POST /{id} returns 403 when caller lacks WRITE on the group" {
            val group = createGroup("Update-blocked")
            mockMvc
                .perform(
                    post("/api/user-groups/${group.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "Renamed" }"""),
                ).andExpect(status().isForbidden)
        }

        "POST /{id} with adminExternalIds promotes a member to ADMIN (WRITE granted via namespace ADMIN)" {
            val group = createGroup("Update-roles")
            userRepository.save(
                User(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "member@example.com",
                    email = "member@example.com",
                    isAdmin = false,
                ),
            )
            userGroupRepository.addUsers(group.id, listOf("member@example.com"))
            grantNamespace(PermissionRelation.ADMIN)

            // Drive the SDK-request -> internal-request mapping through the controller: adminExternalIds
            // must reach the role-reconciliation batch. (The other spec reaches role logic via the
            // service, bypassing it.)
            mockMvc
                .perform(
                    post("/api/user-groups/${group.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{ "name": "${group.name}", "userExternalIdsToAdd": [], "userExternalIdsToRemove": [], "adminExternalIds": ["member@example.com"], "agentIds": [] }""",
                        ),
                ).andExpect(status().isOk)

            mockMvc
                .perform(get("/api/user-groups/${group.id}/members"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.externalId=='member@example.com')].role", contains("ADMIN")))
        }

        "POST /{id} demotes an ADMIN dropped from adminExternalIds back to MEMBER" {
            val group = createGroup("Update-demote")
            userRepository.save(
                User(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "demote-me@example.com",
                    email = "demote-me@example.com",
                    isAdmin = false,
                ),
            )
            userGroupRepository.addUsers(group.id, listOf("demote-me@example.com"))
            grantNamespace(PermissionRelation.ADMIN)

            fun updateWithAdmins(admins: String) =
                mockMvc
                    .perform(
                        post("/api/user-groups/${group.id}")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{ "name": "${group.name}", "userExternalIdsToAdd": [], "userExternalIdsToRemove": [], "adminExternalIds": [$admins], "agentIds": [] }""",
                            ),
                    ).andExpect(status().isOk)

            updateWithAdmins("\"demote-me@example.com\"")
            updateWithAdmins("")

            mockMvc
                .perform(get("/api/user-groups/${group.id}/members"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.externalId=='demote-me@example.com')].role", contains("MEMBER")))
        }

        // -------------------------------------------------------------------------
        // DELETE /{id} — @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'DELETE')")
        // -------------------------------------------------------------------------

        "DELETE returns 403 for a namespace MEMBER (DELETE requires ADMIN)" {
            val group = createGroup("Delete-blocked")
            grantNamespace(PermissionRelation.MEMBER)
            mockMvc
                .perform(delete("/api/user-groups/${group.id}"))
                .andExpect(status().isForbidden)
        }

        "DELETE returns 204 for a namespace ADMIN" {
            val group = createGroup("Deletable")
            grantNamespace(PermissionRelation.ADMIN)
            mockMvc
                .perform(delete("/api/user-groups/${group.id}"))
                .andExpect(status().isNoContent)
        }
    }
}
