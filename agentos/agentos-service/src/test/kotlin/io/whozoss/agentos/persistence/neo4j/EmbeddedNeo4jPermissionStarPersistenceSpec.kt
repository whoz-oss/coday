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
 * Persistence contract for the per-user "starred" (favorite) flag on the
 * user↔entity relation, exercised against the embedded Neo4j harness.
 *
 * Mirrors the embedded-neo4j spec infrastructure used by
 * [EmbeddedNeo4jCasePersistenceSpec] (`embedded-neo4j` profile +
 * [EmbeddedNeo4jTestConfiguration] harness driver — no Docker).
 *
 * Verifies both layers of the plumbing:
 * - the raw Cypher on [PermissionNodeNeo4jRepository] (`setStarred` / `findStarredEntityIds`)
 * - the typed delegation through [PermissionService] (`setStarred` / `listStarredEntityIds`)
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

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "setStarred(true) is visible via findStarredEntityIds; setStarred(false) removes it" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant a direct ADMIN relation the star flag lives on.
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            permissionNodeRepository.setStarred(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
                starred = true,
            )
            permissionNodeRepository.findStarredEntityIds(
                userId = user.id.toString(),
                entityLabel = "Case",
            ) shouldContain case.id.toString()

            permissionNodeRepository.setStarred(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
                starred = false,
            )
            permissionNodeRepository.findStarredEntityIds(
                userId = user.id.toString(),
                entityLabel = "Case",
            ) shouldNotContain case.id.toString()
        }

        "findStarredEntityIds returns empty for a user with no relation on the entity" {
            val user = createUser()
            val namespace = createNamespace()
            createCase(namespace.id) // a case exists but the user has no edge to it

            permissionNodeRepository
                .findStarredEntityIds(
                    userId = user.id.toString(),
                    entityLabel = "Case",
                ).shouldBeEmpty()
        }

        "setStarred is a no-op when the user has no direct relation on the entity" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No ADMIN/MEMBER edge granted — SET must not create anything to star.
            permissionNodeRepository.setStarred(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
                starred = true,
            )

            permissionNodeRepository
                .findStarredEntityIds(
                    userId = user.id.toString(),
                    entityLabel = "Case",
                ).shouldBeEmpty()
        }

        "starred is per-user: it is scoped to the caller's own edge (ADMIN vs MEMBER) and never leaks across users" {
            val userA = createUser("a@example.com")
            val userB = createUser("b@example.com")
            val namespace = createNamespace()
            val case = createCase(namespace.id)
            val caseId = case.id.toString()

            // Two distinct users, each with their OWN direct edge to the same case:
            // A via ADMIN, B via MEMBER (exercises the MEMBER branch of the star queries).
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

            // A stars the case: only A sees it, B does not (per-user isolation on set).
            permissionNodeRepository.setStarred(
                userId = userA.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
                starred = true,
            )
            permissionNodeRepository.findStarredEntityIds(userA.id.toString(), "Case") shouldContain caseId
            permissionNodeRepository.findStarredEntityIds(userB.id.toString(), "Case") shouldNotContain caseId

            // B stars it via its MEMBER edge: B now sees it, and A is unaffected.
            permissionNodeRepository.setStarred(
                userId = userB.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
                starred = true,
            )
            permissionNodeRepository.findStarredEntityIds(userB.id.toString(), "Case") shouldContain caseId
            permissionNodeRepository.findStarredEntityIds(userA.id.toString(), "Case") shouldContain caseId

            // B un-stars: A's star survives (isolation holds on unset too).
            permissionNodeRepository.setStarred(
                userId = userB.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
                starred = false,
            )
            permissionNodeRepository.findStarredEntityIds(userB.id.toString(), "Case") shouldNotContain caseId
            permissionNodeRepository.findStarredEntityIds(userA.id.toString(), "Case") shouldContain caseId
        }

        "PermissionService.setStarred / listStarredEntityIds round-trip via the typed API" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            permissionService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true)
            permissionService.listStarredEntityIds(user.id.toString(), EntityType.CASE) shouldContain case.id.toString()

            permissionService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), false)
            permissionService.listStarredEntityIds(user.id.toString(), EntityType.CASE) shouldNotContain case.id.toString()
        }

        "setStarred returns true when a direct edge exists and false when the user has none" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No direct edge yet — the SET matches nothing, so nothing was persisted.
            permissionService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe false

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case",
            )

            // Direct edge present — the star lands.
            permissionService.setStarred(user.id.toString(), EntityType.CASE, case.id.toString(), true) shouldBe true
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
            permissionService.setStarred(user.id.toString(), EntityType.CASE, adminCase.id.toString(), true)

            val relations = permissionService.listDirectRelations(user.id.toString(), EntityType.CASE)

            relations[adminCase.id.toString()] shouldBe DirectRelation(PermissionRelation.ADMIN, starred = true)
            relations[memberCase.id.toString()] shouldBe DirectRelation(PermissionRelation.MEMBER, starred = false)
            relations.containsKey(unrelatedCase.id.toString()) shouldBe false
        }
    }
}
