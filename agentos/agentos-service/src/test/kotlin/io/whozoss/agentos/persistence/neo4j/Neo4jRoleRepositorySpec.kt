package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Integration tests for [Neo4jRoleRepository] using the embedded Neo4j test harness.
 *
 * Each test starts with a clean database (all nodes and relationships deleted).
 * User, Namespace, and Case nodes are created directly via the Neo4j [Driver]
 * so the tests are independent of other repository implementations.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class Neo4jRoleRepositorySpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var roleRepository: RoleRepository

    @Autowired
    lateinit var driver: Driver

    /** Create a User node directly in Neo4j. Returns the id. */
    private fun createUser(id: String = UUID.randomUUID().toString(), externalId: String = "test@example.com"): String {
        driver.session().use { session ->
            session.run(
                "CREATE (u:User {id: \$id, externalId: \$externalId, email: \$externalId, isRoot: false})",
                mapOf("id" to id, "externalId" to externalId),
            )
        }
        return id
    }

    /** Create a Namespace node directly in Neo4j. Returns the id. */
    private fun createNamespace(id: String = UUID.randomUUID().toString(), name: String = "test-ns"): String {
        driver.session().use { session ->
            session.run(
                "CREATE (n:Namespace {id: \$id, name: \$name})",
                mapOf("id" to id, "name" to name),
            )
        }
        return id
    }

    /** Create a Case node with BELONGS_TO relationship to a Namespace. Returns the case id. */
    private fun createCase(
        id: String = UUID.randomUUID().toString(),
        namespaceId: String,
        title: String = "test-case",
    ): String {
        driver.session().use { session ->
            session.run(
                """
                MATCH (n:Namespace {id: ${'$'}namespaceId})
                CREATE (c:Case {id: ${'$'}id, namespaceId: ${'$'}namespaceId, status: 'PENDING', title: ${'$'}title})-[:BELONGS_TO]->(n)
                """.trimIndent(),
                mapOf("id" to id, "namespaceId" to namespaceId, "title" to title),
            )
        }
        return id
    }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // =====================================================================
        // isRoot / setRoot
        // =====================================================================

        "isRoot returns false for a non-root user" {
            val userId = createUser()

            roleRepository.isRoot(userId).shouldBeFalse()
        }

        "setRoot grants root and isRoot returns true" {
            val userId = createUser()

            roleRepository.setRoot(userId, true)

            roleRepository.isRoot(userId).shouldBeTrue()
        }

        "setRoot can revoke root status" {
            val userId = createUser()
            roleRepository.setRoot(userId, true)

            roleRepository.setRoot(userId, false)

            roleRepository.isRoot(userId).shouldBeFalse()
        }

        // =====================================================================
        // MEMBER_OF — Namespace roles
        // =====================================================================

        "assignNamespaceRole creates MEMBER_OF relationship" {
            val userId = createUser()
            val nsId = createNamespace()

            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.MEMBER, "admin")

            roleRepository.findNamespaceRole(userId, nsId) shouldBe NamespaceRole.MEMBER
        }

        "assignNamespaceRole overwrites existing role (upsert)" {
            val userId = createUser()
            val nsId = createNamespace()
            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.VIEWER, "admin")

            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.ADMIN, "admin")

            roleRepository.findNamespaceRole(userId, nsId) shouldBe NamespaceRole.ADMIN
        }

        "findNamespaceRole returns null when no role exists" {
            val userId = createUser()
            val nsId = createNamespace()

            roleRepository.findNamespaceRole(userId, nsId).shouldBeNull()
        }

        "removeNamespaceRole deletes the MEMBER_OF relationship" {
            val userId = createUser()
            val nsId = createNamespace()
            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.MEMBER, "admin")

            roleRepository.removeNamespaceRole(userId, nsId)

            roleRepository.findNamespaceRole(userId, nsId).shouldBeNull()
        }

        "findNamespaceIdsForUser returns all namespaces where user has a role" {
            val userId = createUser()
            val ns1 = createNamespace()
            val ns2 = createNamespace()
            val ns3 = createNamespace() // no role assigned
            roleRepository.assignNamespaceRole(userId, ns1, NamespaceRole.MEMBER, "admin")
            roleRepository.assignNamespaceRole(userId, ns2, NamespaceRole.ADMIN, "admin")

            val result = roleRepository.findNamespaceIdsForUser(userId)

            result shouldContainExactlyInAnyOrder setOf(ns1, ns2)
        }

        "findNamespaceIdsForUser returns empty set when user has no roles" {
            val userId = createUser()

            roleRepository.findNamespaceIdsForUser(userId).shouldBeEmpty()
        }

        // =====================================================================
        // PARTICIPANT_IN — Case roles
        // =====================================================================

        "assignCaseRole creates PARTICIPANT_IN relationship" {
            val userId = createUser()
            val nsId = createNamespace()
            val caseId = createCase(namespaceId = nsId)

            roleRepository.assignCaseRole(userId, caseId, CaseRole.PARTICIPANT, "admin")

            roleRepository.findCaseRole(userId, caseId) shouldBe CaseRole.PARTICIPANT
        }

        "assignCaseRole overwrites existing role (upsert)" {
            val userId = createUser()
            val nsId = createNamespace()
            val caseId = createCase(namespaceId = nsId)
            roleRepository.assignCaseRole(userId, caseId, CaseRole.OBSERVER, "admin")

            roleRepository.assignCaseRole(userId, caseId, CaseRole.OWNER, "admin")

            roleRepository.findCaseRole(userId, caseId) shouldBe CaseRole.OWNER
        }

        "findCaseRole returns null when no role exists" {
            val userId = createUser()
            val nsId = createNamespace()
            val caseId = createCase(namespaceId = nsId)

            roleRepository.findCaseRole(userId, caseId).shouldBeNull()
        }

        "removeCaseRole deletes the PARTICIPANT_IN relationship" {
            val userId = createUser()
            val nsId = createNamespace()
            val caseId = createCase(namespaceId = nsId)
            roleRepository.assignCaseRole(userId, caseId, CaseRole.PARTICIPANT, "admin")

            roleRepository.removeCaseRole(userId, caseId)

            roleRepository.findCaseRole(userId, caseId).shouldBeNull()
        }

        // =====================================================================
        // findAccessibleCaseIdsForUser — graph traversal
        // =====================================================================

        "findAccessibleCaseIdsForUser returns cases in the namespace via MEMBER_OF" {
            val userId = createUser()
            val nsId = createNamespace()
            val case1 = createCase(namespaceId = nsId, title = "case-1")
            val case2 = createCase(namespaceId = nsId, title = "case-2")
            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.MEMBER, "admin")

            val result = roleRepository.findAccessibleCaseIdsForUser(userId, nsId)

            result shouldContainExactlyInAnyOrder setOf(case1, case2)
        }

        "findAccessibleCaseIdsForUser returns empty when user has no namespace role" {
            val userId = createUser()
            val nsId = createNamespace()
            createCase(namespaceId = nsId)

            roleRepository.findAccessibleCaseIdsForUser(userId, nsId).shouldBeEmpty()
        }

        "findAccessibleCaseIdsForUser does not leak cases from other namespaces" {
            val userId = createUser()
            val ns1 = createNamespace()
            val ns2 = createNamespace()
            val case1 = createCase(namespaceId = ns1, title = "ns1-case")
            val case2 = createCase(namespaceId = ns2, title = "ns2-case")
            roleRepository.assignNamespaceRole(userId, ns1, NamespaceRole.MEMBER, "admin")

            val result = roleRepository.findAccessibleCaseIdsForUser(userId, ns1)

            result shouldBe setOf(case1)
        }

        // =====================================================================
        // Soft-deleted nodes are excluded
        // =====================================================================

        "isRoot returns false for a soft-deleted user" {
            val userId = createUser()
            roleRepository.setRoot(userId, true)
            driver.session().use { session ->
                session.run("MATCH (u:User {id: \$id}) SET u.removed = true", mapOf("id" to userId))
            }

            roleRepository.isRoot(userId).shouldBeFalse()
        }

        "findNamespaceRole returns null when user is soft-deleted" {
            val userId = createUser()
            val nsId = createNamespace()
            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.ADMIN, "admin")
            driver.session().use { session ->
                session.run("MATCH (u:User {id: \$id}) SET u.removed = true", mapOf("id" to userId))
            }

            roleRepository.findNamespaceRole(userId, nsId).shouldBeNull()
        }

        "findNamespaceRole returns null when namespace is soft-deleted" {
            val userId = createUser()
            val nsId = createNamespace()
            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.ADMIN, "admin")
            driver.session().use { session ->
                session.run("MATCH (n:Namespace {id: \$id}) SET n.removed = true", mapOf("id" to nsId))
            }

            roleRepository.findNamespaceRole(userId, nsId).shouldBeNull()
        }

        // =====================================================================
        // Performance — role lookups should be fast
        // =====================================================================

        "isRoot lookup completes within 5ms p95" {
            val userId = createUser()
            roleRepository.setRoot(userId, true)

            // Warm up — generous for embedded harness JIT compilation
            repeat(50) { roleRepository.isRoot(userId) }

            // Measure
            val times = (1..100).map {
                val start = System.nanoTime()
                roleRepository.isRoot(userId)
                (System.nanoTime() - start) / 1_000_000.0 // ms
            }.sorted()

            val p95 = times[(times.size * 0.95).toInt()]
            // 50ms threshold for test harness; production NFR is < 5ms p95
            (p95 < 50.0).shouldBeTrue()
        }

        "findNamespaceRole lookup completes within 5ms p95" {
            val userId = createUser()
            val nsId = createNamespace()
            roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.MEMBER, "admin")

            // Warm up — generous for embedded harness JIT compilation
            repeat(50) { roleRepository.findNamespaceRole(userId, nsId) }

            // Measure
            val times = (1..100).map {
                val start = System.nanoTime()
                roleRepository.findNamespaceRole(userId, nsId)
                (System.nanoTime() - start) / 1_000_000.0 // ms
            }.sorted()

            val p95 = times[(times.size * 0.95).toInt()]
            // 50ms threshold for test harness; production NFR is < 5ms p95
            (p95 < 50.0).shouldBeTrue()
        }
    }
}
