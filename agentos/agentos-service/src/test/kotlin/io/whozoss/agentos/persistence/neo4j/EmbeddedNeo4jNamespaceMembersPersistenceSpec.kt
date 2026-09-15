package io.whozoss.agentos.persistence.neo4j

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespacePermissionService
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionRepository
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Namespace membership lookups and role changes, exercised against the embedded Neo4j harness.
 *
 * Covers the `(userId, relation)` lookup read by [PermissionRepository.listRelationsForUsers] and its
 * consumer [NamespacePermissionService.updateMembers], which relies on it to tell an existing member apart
 * from a user being added, and on the namespace's current ADMINs for its anti-lockout guard.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jNamespaceMembersPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var permissionRepository: PermissionRepository

    @Autowired
    lateinit var permissionService: PermissionService

    @Autowired
    lateinit var namespacePermissionService: NamespacePermissionService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var namespaceRepository: NamespaceRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var driver: Driver

    private fun createUser(externalId: String): User =
        userRepository.save(
            User(
                metadata = EntityMetadata(),
                externalId = externalId,
                email = externalId,
                isAdmin = false,
            ),
        )

    private fun createNamespace(): Namespace =
        namespaceRepository.save(
            Namespace(metadata = EntityMetadata(), name = "members-namespace"),
        )

    private fun grant(
        user: User,
        namespace: Namespace,
        relation: PermissionRelation,
    ) = permissionService.grantPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), relation)

    /** Adds a raw edge without the grant path's opposite-edge cleanup, reproducing legacy dual-edge data. */
    private fun addRawEdge(
        user: User,
        namespace: Namespace,
        relation: PermissionRelation,
    ) = driver.session().use { session ->
        session.run(
            $$"MATCH (u:User {id: $userId}), (n:Namespace {id: $namespaceId}) MERGE (u)-[:$${relation.name}]->(n)",
            mapOf("userId" to user.id.toString(), "namespaceId" to namespace.id.toString()),
        ).consume()
    }

    private fun namespaceAdminIds(namespace: Namespace): List<String> =
        permissionService.listUsersWithPermission(
            EntityType.NAMESPACE,
            namespace.id.toString(),
            PermissionRelation.ADMIN,
        )

    private fun roleChange(
        user: User,
        relation: PermissionRelation,
    ) = listOf(UserMembershipRole(userId = user.id, role = relation.name))

    /** Waits until Neo4j reports a transaction blocked on a lock held by another transaction. */
    private fun awaitBlockedTransaction() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val statuses =
                driver.session().use { session ->
                    session.run("SHOW TRANSACTIONS YIELD status RETURN status").list { it["status"].asString() }
                }
            if (statuses.any { it.startsWith("Blocked") }) return
            Thread.sleep(LOCK_POLL_INTERVAL_MILLIS)
        }
        error("No transaction became blocked within $LOCK_WAIT_TIMEOUT_SECONDS seconds")
    }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "listRelationsForUsers returns the direct relation of each requested user holding one" {
            val admin = createUser("admin@example.com")
            val member = createUser("member@example.com")
            val outsider = createUser("outsider@example.com")
            val namespace = createNamespace()
            grant(admin, namespace, PermissionRelation.ADMIN)
            grant(member, namespace, PermissionRelation.MEMBER)

            val relations =
                permissionRepository.listRelationsForUsers(
                    entityType = EntityType.NAMESPACE,
                    entityId = namespace.id.toString(),
                    userIds = listOf(admin, member, outsider).map { it.id.toString() },
                )

            relations shouldBe
                mapOf(
                    admin.id.toString() to PermissionRelation.ADMIN,
                    member.id.toString() to PermissionRelation.MEMBER,
                )
        }

        PermissionRelation.entries.forEach { firstEdge ->
            val secondEdge = PermissionRelation.entries.single { it != firstEdge }

            "listRelationsForUsers reports ADMIN for a user holding both edges ($firstEdge then $secondEdge)" {
                val user = createUser("dual-edge@example.com")
                val namespace = createNamespace()
                grant(user, namespace, firstEdge)
                addRawEdge(user, namespace, secondEdge)

                val relations =
                    permissionRepository.listRelationsForUsers(
                        entityType = EntityType.NAMESPACE,
                        entityId = namespace.id.toString(),
                        userIds = listOf(user.id.toString()),
                    )

                relations shouldBe mapOf(user.id.toString() to PermissionRelation.ADMIN)
            }
        }

        "listRelationsForUsers ignores a soft-deleted user whose relation outlives the deletion" {
            val deleted = createUser("deleted@example.com")
            val namespace = createNamespace()
            grant(deleted, namespace, PermissionRelation.ADMIN)
            userRepository.delete(deleted.id)

            val relations =
                permissionRepository.listRelationsForUsers(
                    entityType = EntityType.NAMESPACE,
                    entityId = namespace.id.toString(),
                    userIds = listOf(deleted.id.toString()),
                )

            relations shouldBe emptyMap()
        }

        "updateMembers demotes an existing ADMIN to MEMBER when another ADMIN remains" {
            val keptAdmin = createUser("kept-admin@example.com")
            val demoted = createUser("demoted@example.com")
            val namespace = createNamespace()
            grant(keptAdmin, namespace, PermissionRelation.ADMIN)
            grant(demoted, namespace, PermissionRelation.ADMIN)

            val members =
                namespacePermissionService.updateMembers(
                    namespaceId = namespace.id,
                    members = roleChange(demoted, PermissionRelation.MEMBER),
                    callerIsSuperAdmin = true,
                )

            members.map { it.id to it.role } shouldContainExactlyInAnyOrder
                listOf(
                    keptAdmin.id to PermissionRelation.ADMIN.name,
                    demoted.id to PermissionRelation.MEMBER.name,
                )
            namespaceAdminIds(namespace) shouldBe listOf(keptAdmin.id.toString())
        }

        "updateMembers lets a non-super-admin change the role of a user already on the namespace" {
            val admin = createUser("ns-admin@example.com")
            val member = createUser("promoted@example.com")
            val namespace = createNamespace()
            grant(admin, namespace, PermissionRelation.ADMIN)
            grant(member, namespace, PermissionRelation.MEMBER)

            val members =
                namespacePermissionService.updateMembers(
                    namespaceId = namespace.id,
                    members = roleChange(member, PermissionRelation.ADMIN),
                    callerIsSuperAdmin = false,
                )

            members.map { it.id to it.role } shouldContainExactlyInAnyOrder
                listOf(
                    admin.id to PermissionRelation.ADMIN.name,
                    member.id to PermissionRelation.ADMIN.name,
                )
            namespaceAdminIds(namespace) shouldContainExactlyInAnyOrder
                listOf(admin.id.toString(), member.id.toString())
        }

        "updateMembers refuses to demote the last active ADMIN when the other ADMIN is a soft-deleted user" {
            val activeAdmin = createUser("active-admin@example.com")
            val deletedAdmin = createUser("deleted-admin@example.com")
            val namespace = createNamespace()
            grant(activeAdmin, namespace, PermissionRelation.ADMIN)
            grant(deletedAdmin, namespace, PermissionRelation.ADMIN)
            userRepository.delete(deletedAdmin.id)

            shouldThrow<UnprocessableEntityException> {
                namespacePermissionService.updateMembers(
                    namespaceId = namespace.id,
                    members = roleChange(activeAdmin, PermissionRelation.MEMBER),
                    callerIsSuperAdmin = true,
                )
            }
            namespaceAdminIds(namespace) shouldContainExactlyInAnyOrder
                listOf(activeAdmin.id.toString(), deletedAdmin.id.toString())
        }

        "updateMembers serializes concurrent updates so two crossed demotions cannot remove every ADMIN" {
            val adminA = createUser("admin-a@example.com")
            val adminB = createUser("admin-b@example.com")
            val namespace = createNamespace()
            grant(adminA, namespace, PermissionRelation.ADMIN)
            grant(adminB, namespace, PermissionRelation.ADMIN)
            val firstUpdateApplied = CountDownLatch(1)
            val releaseFirstUpdate = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                // The first update stays uncommitted until released, holding its locks on the namespace.
                val firstUpdate =
                    executor.submit(
                        Callable {
                            TransactionTemplate(transactionManager).executeWithoutResult {
                                namespacePermissionService.updateMembers(
                                    namespaceId = namespace.id,
                                    members = roleChange(adminB, PermissionRelation.MEMBER),
                                    callerIsSuperAdmin = true,
                                )
                                firstUpdateApplied.countDown()
                                releaseFirstUpdate.await(LOCK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            }
                        },
                    )
                firstUpdateApplied.await(LOCK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
                val crossedUpdate =
                    executor.submit(
                        Callable {
                            namespacePermissionService.updateMembers(
                                namespaceId = namespace.id,
                                members = roleChange(adminA, PermissionRelation.MEMBER),
                                callerIsSuperAdmin = true,
                            )
                        },
                    )
                awaitBlockedTransaction()
                releaseFirstUpdate.countDown()
                firstUpdate.get(LOCK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                val failure =
                    shouldThrow<ExecutionException> { crossedUpdate.get(LOCK_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }

                failure.cause.shouldBeInstanceOf<UnprocessableEntityException>()
                namespaceAdminIds(namespace) shouldBe listOf(adminA.id.toString())
            } finally {
                releaseFirstUpdate.countDown()
                executor.shutdownNow()
            }
        }
    }

    companion object {
        private const val LOCK_WAIT_TIMEOUT_SECONDS = 30L
        private const val LOCK_POLL_INTERVAL_MILLIS = 50L
    }
}
