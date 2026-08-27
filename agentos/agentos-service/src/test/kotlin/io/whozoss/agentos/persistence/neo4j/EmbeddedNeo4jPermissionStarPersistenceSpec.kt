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
import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
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
import java.time.Instant
import java.util.UUID

/**
 * Persistence contract for per-user case state (starred flag + read timestamp),
 * exercised against the embedded Neo4j harness.
 *
 * Since issue #1140 (and the read-tracking feature), state is stored on a
 * `[:HAS_USER_CASE_STATE]` relationship-with-properties (replacing the legacy
 * `[:STARRED]` plain edge). The edge carries:
 * - `favoriteAt: Instant?` — non-null when the user has starred (favorited) the case.
 * - `readAt: Instant?` — timestamp of the user's last read; null = never read.
 *
 * Verifies both layers of the plumbing:
 * - raw Cypher on [CaseNodeNeo4jRepository] (`mergeStarred` / `deleteStarred` /
 *   `markRead` / `countUnread` / `findDirectRelations`)
 * - the typed delegation through [StarredService] (`setStarred` / `listDirectRelations`)
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jPermissionStarPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository

    @Autowired
    lateinit var caseNodeRepository: CaseNodeNeo4jRepository

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

    /** Case ids the user has starred, resolved via the [StarredService.listDirectRelations] API. */
    private fun starredIds(userId: String): Set<String> =
        starredService.listDirectRelations(userId, EntityType.CASE).filterValues { it.starred }.keys

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "mergeStarred creates a [:HAS_USER_CASE_STATE] edge with favoriteAt; deleteStarred clears it" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // A direct ADMIN relation is required: mergeStarred guards against orphaned edges.
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            caseNodeRepository.mergeStarred(
                userId = user.id.toString(),
                caseId = case.id.toString(),
                favoriteAt = Instant.now(),
            )
            starredIds(user.id.toString()) shouldContain case.id.toString()

            caseNodeRepository.deleteStarred(
                userId = user.id.toString(),
                caseId = case.id.toString(),
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

            // No ADMIN/MEMBER edge — the MATCH guard prevents orphaned [:HAS_USER_CASE_STATE] edges.
            caseNodeRepository.mergeStarred(
                userId = user.id.toString(),
                caseId = case.id.toString(),
                favoriteAt = Instant.now(),
            )

            starredIds(user.id.toString()).shouldBeEmpty()
        }

        "starred is per-user: it is scoped to the caller's edge and never leaks across users" {
            val userA = createUser("a@example.com")
            val userB = createUser("b@example.com")
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()

            // Two distinct users, each with their OWN direct permission edge to the same case.
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

            // A stars the case: only A has a [:HAS_USER_CASE_STATE] edge with favoriteAt, B does not.
            caseNodeRepository.mergeStarred(
                userId = userA.id.toString(),
                caseId = caseId,
                favoriteAt = Instant.now(),
            )
            starredIds(userA.id.toString()) shouldContain caseId
            starredIds(userB.id.toString()) shouldNotContain caseId

            // B stars it: B now has its own edge, A is unaffected.
            caseNodeRepository.mergeStarred(
                userId = userB.id.toString(),
                caseId = caseId,
                favoriteAt = Instant.now(),
            )
            starredIds(userB.id.toString()) shouldContain caseId
            starredIds(userA.id.toString()) shouldContain caseId

            // B un-stars: only B's favoriteAt is cleared, A's survives.
            caseNodeRepository.deleteStarred(
                userId = userB.id.toString(),
                caseId = caseId,
            )
            starredIds(userB.id.toString()) shouldNotContain caseId
            starredIds(userA.id.toString()) shouldContain caseId
        }

        "a user holding both [:ADMIN] and [:MEMBER] on one case collapses to a single ADMIN entry" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()
            val userId = user.id.toString()

            // Dual edges can no longer be produced through the permission API but may exist as
            // legacy data — build the state with raw Cypher to verify the defensive collapse.
            permissionNodeRepository.createAdminPermission(userId = userId, entityId = caseId, entityLabel = "Case")
            driver.session().use { session ->
                session.run(
                    $$"MATCH (u:User {id: $userId}), (c:Case {id: $caseId}) MERGE (u)-[:MEMBER]->(c)",
                    mapOf("userId" to userId, "caseId" to caseId),
                )
            }

            // mergeStarred's MATCH yields two rows; MERGE is idempotent → a single [:HAS_USER_CASE_STATE] edge.
            caseNodeRepository.mergeStarred(userId = userId, caseId = caseId, favoriteAt = Instant.now())

            // findDirectRelations emits two rows for the same case id; the decode collapses them (ADMIN wins).
            val starred = starredService.listDirectRelations(userId, EntityType.CASE)
            starred.size shouldBe 1
            starred[caseId]?.starred shouldBe true
            starred[caseId]?.relation shouldBe PermissionRelation.ADMIN

            // deleteStarred clears the single edge despite the two matching rows.
            caseNodeRepository.deleteStarred(userId = userId, caseId = caseId)
            val cleared = starredService.listDirectRelations(userId, EntityType.CASE)
            cleared[caseId]?.starred shouldBe false
            cleared[caseId]?.relation shouldBe PermissionRelation.ADMIN
        }

        "StarredService.setStarred round-trip visible via listDirectRelations" {
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

            // No direct edge yet — the MATCH guard prevents orphaned edges.
            starredService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe false

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            // Direct edge present — the star lands.
            starredService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe true
        }

        "[:HAS_USER_CASE_STATE] favoriteAt survives a MEMBER-to-ADMIN promotion" {
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
            caseNodeRepository.mergeStarred(
                userId = userId,
                caseId = caseId,
                favoriteAt = Instant.now(),
            )
            starredIds(userId) shouldContain caseId

            // Promote: [:MEMBER] is replaced by [:ADMIN]; the state edge is a separate edge and untouched.
            permissionService.promoteMemberToAdmin(userId, EntityType.CASE, caseId)

            val relations = starredService.listDirectRelations(userId, EntityType.CASE)
            relations[caseId]?.starred shouldBe true
            relations[caseId]?.relation shouldBe PermissionRelation.ADMIN
        }

        "[:HAS_USER_CASE_STATE] favoriteAt survives an ADMIN-to-MEMBER demotion" {
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
            caseNodeRepository.mergeStarred(
                userId = userId,
                caseId = caseId,
                favoriteAt = Instant.now(),
            )
            starredIds(userId) shouldContain caseId

            // Demote: [:ADMIN] is replaced by [:MEMBER]; the state edge is untouched.
            permissionService.demoteAdminToMember(userId, EntityType.CASE, caseId)

            val relations = starredService.listDirectRelations(userId, EntityType.CASE)
            relations[caseId]?.starred shouldBe true
            relations[caseId]?.relation shouldBe PermissionRelation.MEMBER
        }

        "listDirectRelations returns the caller's relation and starred flag per entity (and omits un-related ones)" {
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

            val relations = starredService.listDirectRelations(user.id.toString(), EntityType.CASE)

            relations[adminCase.id.toString()]?.starred shouldBe true
            relations[adminCase.id.toString()]?.relation shouldBe PermissionRelation.ADMIN
            relations[memberCase.id.toString()]?.starred shouldBe false
            relations[memberCase.id.toString()]?.relation shouldBe PermissionRelation.MEMBER
            relations.containsKey(unrelatedCase.id.toString()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // markRead / countUnread
        // -------------------------------------------------------------------------

        "markRead sets readAt on the HAS_USER_CASE_STATE edge; countUnread decrements" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val userId = user.id.toString()
            val caseId = case.id.toString()
            val namespaceId = namespace.id.toString()

            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )

            // Before any read: case is unread.
            caseNodeRepository.countUnread(userId = userId, namespaceId = namespaceId) shouldBe 1L

            caseNodeRepository.markRead(userId = userId, caseId = caseId, readAt = Instant.now())

            // After markRead: case with no events since readAt is considered read.
            caseNodeRepository.countUnread(userId = userId, namespaceId = namespaceId) shouldBe 0L
        }

        "countUnread counts only cases the user has a direct edge on" {
            val user = createUser()
            val otherUser = createUser("other@example.com")
            val namespace = createNamespace()
            val myCase = createCase(namespace.id)
            val theirCase = createCase(namespace.id)
            val userId = user.id.toString()
            val namespaceId = namespace.id.toString()

            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = myCase.id.toString(),
                entityLabel = "Case",
            )
            permissionNodeRepository.createAdminPermission(
                userId = otherUser.id.toString(),
                entityId = theirCase.id.toString(),
                entityLabel = "Case",
            )

            // user has access only to myCase, not theirCase.
            caseNodeRepository.countUnread(userId = userId, namespaceId = namespaceId) shouldBe 1L
        }

        "readAt is preserved when favoriteAt is also set on the same edge" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val userId = user.id.toString()
            val caseId = case.id.toString()

            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )

            val readTime = Instant.parse("2025-06-01T10:00:00Z")
            caseNodeRepository.markRead(userId = userId, caseId = caseId, readAt = readTime)
            // Now also star the case — should not clear readAt.
            caseNodeRepository.mergeStarred(userId = userId, caseId = caseId, favoriteAt = Instant.now())

            val relations = starredService.listDirectRelations(userId, EntityType.CASE)
            relations[caseId]?.starred shouldBe true
            // readAt must still be present (not cleared by the subsequent mergeStarred).
            relations[caseId]?.readAt shouldBe readTime

        }
    }
}
