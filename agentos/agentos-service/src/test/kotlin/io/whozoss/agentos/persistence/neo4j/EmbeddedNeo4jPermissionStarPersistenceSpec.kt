package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionNodeNeo4jRepository
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.FavoriteService
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
 * Persistence contract for per-user case state (favorite flag + read timestamp),
 * exercised against the embedded Neo4j harness.
 *
 * Since issue #1140 (and the read-tracking feature), state is stored on a
 * `[:WATCHES]` relationship-with-properties (replacing the legacy
 * `[:STARRED]` plain edge). The edge carries:
 * - `favorite: Boolean` — true when the user has favorited the case.
 * - `readAt: Instant?` — timestamp of the user's last read; null = never read.
 *
 * Verifies both layers of the plumbing:
 * - raw Cypher on [CaseNodeNeo4jRepository] (`mergeFavorite` / `clearFavorite` /
 *   `markRead` / `countUnread` / `findDirectRelations`)
 * - the typed delegation through [FavoriteService] (`setFavorite` / `listDirectRelations`)
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
    lateinit var favoriteService: FavoriteService

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

    /** Case ids the user has favorited, resolved via the [FavoriteService.listDirectRelations] API. */
    private fun favoriteIds(userId: String): Set<String> =
        favoriteService.listDirectRelations(userId, EntityType.CASE).filterValues { it.favorite }.keys

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "mergeFavorite creates a [:WATCHES] edge with favorite=true; clearFavorite sets it to false" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // A direct ADMIN relation is required: mergeFavorite guards against orphaned edges.
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            caseNodeRepository.mergeFavorite(
                userId = user.id.toString(),
                caseId = case.id.toString(),
            )
            favoriteIds(user.id.toString()) shouldContain case.id.toString()

            caseNodeRepository.clearFavorite(
                userId = user.id.toString(),
                caseId = case.id.toString(),
            )
            favoriteIds(user.id.toString()) shouldNotContain case.id.toString()
        }

        "no favorite ids are returned for a user with no relation on the entity" {
            val user = createUser()
            val namespace = createNamespace()
            createCase(namespace.id) // a case exists but the user has no edge to it

            favoriteIds(user.id.toString()).shouldBeEmpty()
        }

        "mergeFavorite is a no-op when the user has no direct relation on the entity" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No ADMIN/MEMBER edge — the MATCH guard prevents orphaned [:WATCHES] edges.
            caseNodeRepository.mergeFavorite(
                userId = user.id.toString(),
                caseId = case.id.toString(),
            )

            favoriteIds(user.id.toString()).shouldBeEmpty()
        }

        "favorite is per-user: it is scoped to the caller's edge and never leaks across users" {
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

            // A favorites the case: only A has a [:WATCHES] edge with favorite=true, B does not.
            caseNodeRepository.mergeFavorite(
                userId = userA.id.toString(),
                caseId = caseId,
            )
            favoriteIds(userA.id.toString()) shouldContain caseId
            favoriteIds(userB.id.toString()) shouldNotContain caseId

            // B favorites it: B now has its own edge, A is unaffected.
            caseNodeRepository.mergeFavorite(
                userId = userB.id.toString(),
                caseId = caseId,
            )
            favoriteIds(userB.id.toString()) shouldContain caseId
            favoriteIds(userA.id.toString()) shouldContain caseId

            // B unfavorites: only B's favorite flag is cleared, A's survives.
            caseNodeRepository.clearFavorite(
                userId = userB.id.toString(),
                caseId = caseId,
            )
            favoriteIds(userB.id.toString()) shouldNotContain caseId
            favoriteIds(userA.id.toString()) shouldContain caseId
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

            // mergeFavorite's MATCH yields two rows; MERGE is idempotent → a single [:WATCHES] edge.
            caseNodeRepository.mergeFavorite(userId = userId, caseId = caseId)

            // findDirectRelations emits two rows for the same case id; the decode collapses them (ADMIN wins).
            val starred = favoriteService.listDirectRelations(userId, EntityType.CASE)
            starred.size shouldBe 1
            starred[caseId]?.favorite shouldBe true
            starred[caseId]?.relation shouldBe PermissionRelation.ADMIN

            // clearFavorite clears the single edge despite the two matching rows.
            caseNodeRepository.clearFavorite(userId = userId, caseId = caseId)
            val cleared = favoriteService.listDirectRelations(userId, EntityType.CASE)
            cleared[caseId]?.favorite shouldBe false
            cleared[caseId]?.relation shouldBe PermissionRelation.ADMIN
        }

        "FavoriteService.setFavorite round-trip visible via listDirectRelations" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            favoriteService.setFavorite(user.id.toString(), EntityType.CASE, case.id.toString(), true)
            favoriteIds(user.id.toString()) shouldContain case.id.toString()

            favoriteService.setFavorite(user.id.toString(), EntityType.CASE, case.id.toString(), false)
            favoriteIds(user.id.toString()) shouldNotContain case.id.toString()
        }

        "setFavorite returns true when a direct edge exists and false when the user has none" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No direct edge yet — the MATCH guard prevents orphaned edges.
            favoriteService.setFavorite(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe false

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            // Direct edge present — the favorite lands.
            favoriteService.setFavorite(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe true
        }

        "[:WATCHES] favorite flag survives a MEMBER-to-ADMIN promotion" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()
            val userId = user.id.toString()

            // User starts as MEMBER and favorites the case.
            permissionNodeRepository.createMemberPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )
            caseNodeRepository.mergeFavorite(
                userId = userId,
                caseId = caseId,
            )
            favoriteIds(userId) shouldContain caseId

            // Promote: [:MEMBER] is replaced by [:ADMIN]; the [:WATCHES] edge is separate and untouched.
            permissionService.promoteMemberToAdmin(userId, EntityType.CASE, caseId)

            val relations = favoriteService.listDirectRelations(userId, EntityType.CASE)
            relations[caseId]?.favorite shouldBe true
            relations[caseId]?.relation shouldBe PermissionRelation.ADMIN
        }

        "[:WATCHES] favorite flag survives an ADMIN-to-MEMBER demotion" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()
            val userId = user.id.toString()

            // User starts as ADMIN and favorites the case.
            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )
            caseNodeRepository.mergeFavorite(
                userId = userId,
                caseId = caseId,
            )
            favoriteIds(userId) shouldContain caseId

            // Demote: [:ADMIN] is replaced by [:MEMBER]; the [:WATCHES] edge is untouched.
            permissionService.demoteAdminToMember(userId, EntityType.CASE, caseId)

            val relations = favoriteService.listDirectRelations(userId, EntityType.CASE)
            relations[caseId]?.favorite shouldBe true
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
            favoriteService.setFavorite(user.id.toString(), EntityType.CASE, adminCase.id.toString(), true)

            val relations = favoriteService.listDirectRelations(user.id.toString(), EntityType.CASE)

            relations[adminCase.id.toString()]?.favorite shouldBe true
            relations[adminCase.id.toString()]?.relation shouldBe PermissionRelation.ADMIN
            relations[memberCase.id.toString()]?.favorite shouldBe false
            relations[memberCase.id.toString()]?.relation shouldBe PermissionRelation.MEMBER
            relations.containsKey(unrelatedCase.id.toString()) shouldBe false
        }

        // -------------------------------------------------------------------------
        // markRead / countUnread
        // -------------------------------------------------------------------------

        "markRead sets readAt on the WATCHES edge; countUnread decrements" {
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

        "readAt is preserved when favorite is also set on the same edge" {
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
            // Now also favorite the case — should not clear readAt.
            caseNodeRepository.mergeFavorite(userId = userId, caseId = caseId)

            val relations = favoriteService.listDirectRelations(userId, EntityType.CASE)
            relations[caseId]?.favorite shouldBe true
            // readAt must still be present (not cleared by the subsequent mergeFavorite).
            relations[caseId]?.readAt shouldBe readTime
        }
    }
}
