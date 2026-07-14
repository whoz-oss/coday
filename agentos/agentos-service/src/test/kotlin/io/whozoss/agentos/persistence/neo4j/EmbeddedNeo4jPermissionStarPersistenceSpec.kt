package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.permissions.DirectRelation
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionNodeNeo4jRepository
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.StarredService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Persistence contract for the per-user "starred" (favorite) flag, exercised
 * against the embedded Neo4j harness.
 *
 * Since issue #1140, starred is stored as a dedicated `[:STARRED]` relationship
 * — orthogonal to `[:ADMIN]`/`[:MEMBER]`. Role transitions (promote/demote) no
 * longer need to preserve properties; the `[:STARRED]` edge simply survives.
 *
 * Mirrors the embedded-neo4j spec infrastructure used by
 * [EmbeddedNeo4jCasePersistenceSpec] (`embedded-neo4j` profile +
 * [EmbeddedNeo4jTestConfiguration] harness driver — no Docker).
 *
 * Verifies both layers of the plumbing:
 * - the raw Cypher on [PermissionNodeNeo4jRepository] (`mergeStarred` / `deleteStarred` / `findDirectRelations`)
 * - the typed delegation through [StarredService] (`setStarred` / `listStarred`)
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jPermissionStarPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository

    @Autowired
    lateinit var permissionService: PermissionService

    @Autowired
    lateinit var starredService: StarredService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var namespaceRepository: NamespaceRepository

    @Autowired
    lateinit var caseRepository: CaseRepository

    @Autowired
    lateinit var driver: Driver

    private fun createUser(externalId: String = "test@example.com"): User =
        userRepository.save(
            User(
                metadata = EntityMetadata(),
                externalId = externalId,
                email = externalId,
                isAdmin = false,
            ),
        )

    private fun createNamespace(name: String = "test-namespace"): Namespace =
        namespaceRepository.save(
            Namespace(metadata = EntityMetadata(), name = name),
        )

    private fun createCase(namespaceId: UUID): Case =
        caseRepository.save(
            Case(metadata = EntityMetadata(), namespaceId = namespaceId),
        )

    /** Case ids the user has starred, resolved via the [StarredService.listStarred] API. */
    private fun starredIds(userId: String): Set<String> =
        starredService.listStarred(userId, EntityType.CASE).filterValues { it.starred }.keys

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "mergeStarred creates a [:STARRED] edge; deleteStarred removes it" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // A direct ADMIN relation is required: mergeStarred guards against orphaned [:STARRED] edges.
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            permissionNodeRepository.mergeStarred(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )
            starredIds(user.id.toString()) shouldContain case.id.toString()

            permissionNodeRepository.deleteStarred(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )
            starredIds(user.id.toString()) shouldNotContain case.id.toString()
        }

        "no starred ids are returned for a user with no relation on the entity" {
            val user = createUser()
            val namespace = createNamespace()
            createCase(namespace.id) // a case exists but the user has no edge to it

            starredIds(user.id.toString()).shouldBeEmpty()
        }

        "mergeStarred is a no-op when the user has no direct relation on the entity" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No ADMIN/MEMBER edge — the MATCH guard prevents orphaned [:STARRED] edges.
            permissionNodeRepository.mergeStarred(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            starredIds(user.id.toString()).shouldBeEmpty()
        }

        "starred is per-user: it is scoped to the caller's [:STARRED] edge and never leaks across users" {
            val userA = createUser("a@example.com")
            val userB = createUser("b@example.com")
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()

            // Two distinct users, each with their OWN direct permission edge to the same case:
            // A via ADMIN, B via MEMBER (exercises the MEMBER branch of the mergeStarred guard).
            permissionNodeRepository.createAdminPermission(
                userId = userA.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )
            permissionNodeRepository.createMemberPermission(
                userId = userB.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )

            // A stars the case: only A has a [:STARRED] edge, B does not.
            permissionNodeRepository.mergeStarred(
                userId = userA.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )
            starredIds(userA.id.toString()) shouldContain caseId
            starredIds(userB.id.toString()) shouldNotContain caseId

            // B stars it: B now has its own [:STARRED] edge, A is unaffected.
            permissionNodeRepository.mergeStarred(
                userId = userB.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )
            starredIds(userB.id.toString()) shouldContain caseId
            starredIds(userA.id.toString()) shouldContain caseId

            // B un-stars: only B's [:STARRED] edge is removed, A's survives.
            permissionNodeRepository.deleteStarred(
                userId = userB.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )
            starredIds(userB.id.toString()) shouldNotContain caseId
            starredIds(userA.id.toString()) shouldContain caseId
        }

        "StarredService.setStarred round-trip visible via listStarred" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            starredService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true)
            starredIds(user.id.toString()) shouldContain case.id.toString()

            starredService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), false)
            starredIds(user.id.toString()) shouldNotContain case.id.toString()
        }

        "setStarred returns true when a direct edge exists and false when the user has none" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No direct edge yet — the MATCH guard prevents orphaned [:STARRED] edges.
            starredService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe false

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            // Direct edge present — the star lands.
            starredService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe true
        }

        "[:STARRED] edge survives a MEMBER-to-ADMIN promotion" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()
            val userId = user.id.toString()

            // User starts as MEMBER and stars the case.
            permissionNodeRepository.createMemberPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )
            permissionNodeRepository.mergeStarred(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )
            starredIds(userId) shouldContain caseId

            // Promote: [:MEMBER] is replaced by [:ADMIN]; [:STARRED] is a separate edge and untouched.
            permissionService.promoteMemberToAdmin(userId, EntityType.CASE, caseId)

            val relations = starredService.listStarred(userId, EntityType.CASE)
            relations[caseId] shouldBe DirectRelation(PermissionRelation.ADMIN, starred = true)
        }

        "[:STARRED] edge survives an ADMIN-to-MEMBER demotion" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()
            val userId = user.id.toString()

            // User starts as ADMIN and stars the case.
            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )
            permissionNodeRepository.mergeStarred(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )
            starredIds(userId) shouldContain caseId

            // Demote: [:ADMIN] is replaced by [:MEMBER]; [:STARRED] is a separate edge and untouched.
            permissionService.demoteAdminToMember(userId, EntityType.CASE, caseId)

            val relations = starredService.listStarred(userId, EntityType.CASE)
            relations[caseId] shouldBe DirectRelation(PermissionRelation.MEMBER, starred = true)
        }

        "listStarred returns the caller's relation and [:STARRED] flag per entity (and omits un-related ones)" {
            val user = createUser()
            val namespace = createNamespace()
            val adminCase = createCase(namespace.id)
            val memberCase = createCase(namespace.id)
            val unrelatedCase = createCase(namespace.id) // user has NO edge on this one

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = adminCase.id.toString(),
                entityLabel = "Case",
            )
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = memberCase.id.toString(),
                entityLabel = "Case",
            )
            starredService.setStarred(user.id.toString(), EntityType.CASE, adminCase.id.toString(), true)

            val relations = starredService.listStarred(user.id.toString(), EntityType.CASE)

            relations[adminCase.id.toString()] shouldBe DirectRelation(PermissionRelation.ADMIN, starred = true)
            relations[memberCase.id.toString()] shouldBe DirectRelation(PermissionRelation.MEMBER, starred = false)
            relations.containsKey(unrelatedCase.id.toString()) shouldBe false
        }
    }
}
