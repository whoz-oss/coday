package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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

/**
 * Namespace membership lookups and role changes, exercised against the embedded Neo4j harness.
 *
 * Covers the `(userId, relation)` lookup read by [PermissionRepository.listRelationsForUsers] and its
 * consumer [NamespacePermissionService.updateMembers], which relies on it to tell an existing member apart
 * from a user being added.
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
    }
}
