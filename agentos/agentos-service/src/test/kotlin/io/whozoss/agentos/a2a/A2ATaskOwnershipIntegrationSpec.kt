package io.whozoss.agentos.a2a

import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Integration test for A2A task ownership against the **real** Neo4j-backed
 * [PermissionService] (embedded), in the default `local` security mode.
 *
 * Purpose: prove that a task created through A2A is owned by the caller exactly like a
 * case created through `POST /api/cases`, i.e. that it shows up in
 * `findConcerningUserInNamespace` — the query behind `GET /api/cases/by-parentId/{ns}/mine`,
 * which powers the AgentOS drawer. Before the ownership fix, [A2AService.createCase]
 * persisted the case without the direct `[:ADMIN]` edge, so A2A tasks were invisible.
 *
 * It also checks the flip side: the permission gates added on `tasks/get` / `tasks/cancel`
 * do **not** lock the legitimate caller out of their own tasks in local mode, while a
 * different identity is denied.
 *
 * [SecurityService] is the only mocked bean: it stands in for the OS-username lookup that
 * `local` mode performs, so the test can act as two distinct identities. Everything
 * downstream ([UserService.getCurrentUser], the permission graph, the case repository)
 * is the real thing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class A2ATaskOwnershipIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var a2aService: A2AService

    @Autowired lateinit var caseService: CaseService

    @Autowired lateinit var namespaceService: NamespaceService

    @Autowired lateinit var userService: UserService

    @Autowired lateinit var permissionService: PermissionService

    @MockkBean lateinit var securityService: SecurityService

    /**
     * Impersonate [identity] for the duration of [block], as `local` mode would.
     *
     * Mocking [SecurityService] covers everything reached through
     * [UserService.getCurrentUser]; the [SecurityContextHolder] is populated in addition
     * because a few `@PreAuthorize` expressions on service methods (e.g.
     * `CaseServiceImpl.findConcerningUserInNamespace`) read `authentication.name`. In
     * production both are fed from the same identity by `AgentOsAuthenticationFilter`.
     */
    private fun <T> asIdentity(identity: String, block: () -> T): T {
        every { securityService.resolveCurrentIdentity() } returns identity
        SecurityContextHolder.getContext().authentication =
            AgentOsAuthentication(userService.resolveOrCreateByExternalId(identity))
        return try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    init {

        "an A2A-created task is owned by its caller and listed among their cases" {
            // The very first user in an empty DB is auto-promoted to super-admin, which
            // would bypass every permission check and void the assertions below.
            asIdentity("bootstrap-decoy") { userService.getCurrentUser() }

            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "a2a-ownership"),
            )

            asIdentity("vincent") {
                val owner = userService.getCurrentUser()
                owner.isAdmin shouldBe false

                val case = a2aService.createCase(ns.id, "review the changelog")

                // The regression: the direct user↔case edge behind /by-parentId/{ns}/mine.
                caseService.findConcerningUserInNamespace(owner.id, ns.id).map { it.id } shouldContain case.id

                // ...which is an ADMIN edge, so the owner keeps full control of the task.
                permissionService.hasPermission(
                    owner.id.toString(), EntityType.CASE, case.id.toString(), Action.READ,
                ) shouldBe true
                permissionService.hasPermission(
                    owner.id.toString(), EntityType.CASE, case.id.toString(), Action.DELETE,
                ) shouldBe true
            }
        }

        "the caller can still get and cancel their own task in local mode" {
            asIdentity("bootstrap-decoy") { userService.getCurrentUser() }
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "a2a-self-access"),
            )

            asIdentity("vincent") {
                val case = a2aService.createCase(ns.id, "some task")

                a2aService.getTask(case.id).id shouldBe case.id.toString()
                a2aService.requireCase(case.id).id shouldBe case.id

                a2aService.cancelTask(case.id)
                caseService.findById(case.id)!!.status shouldBe CaseStatus.KILLED
            }
        }

        "another identity can neither read nor cancel a task it does not own" {
            asIdentity("bootstrap-decoy") { userService.getCurrentUser() }
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "a2a-isolation"),
            )

            val case = asIdentity("vincent") { a2aService.createCase(ns.id, "private task") }

            asIdentity("mallory") {
                val intruder = userService.getCurrentUser()
                intruder.isAdmin shouldBe false

                // Reported as not-found rather than forbidden, so task UUIDs cannot be probed.
                shouldThrow<ResourceNotFoundException> { a2aService.getTask(case.id) }
                shouldThrow<ResourceNotFoundException> { a2aService.requireCase(case.id) }
                shouldThrow<ResourceNotFoundException> { a2aService.cancelTask(case.id) }
                shouldThrow<ResourceNotFoundException> {
                    a2aService.getOrCreateCase(ns.id, case.id.toString(), "follow-up")
                }

                caseService.findConcerningUserInNamespace(intruder.id, ns.id)
                    .map { it.id } shouldNotContain case.id
            }

            // The refused cancel really was a no-op.
            caseService.findById(case.id)!!.status shouldBe CaseStatus.PENDING
        }
    }
}
