package io.whozoss.agentos.permissions

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSpec
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Integration tests for Story 1.3: Neo4j Permission Relations (ADMIN/MEMBER).
 *
 * Verifies that permission relationships are correctly created, queried, and deleted
 * in a real Neo4j instance using Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
class Neo4jPermissionRelationsSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
            Neo4jContainerSpec.registerProperties(registry)
    }

    @Autowired
    lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var namespaceRepository: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    private fun createUser(externalId: String = "test@example.com", isAdmin: Boolean = false): User =
        userRepository.save(
            User(
                metadata = EntityMetadata(),
                externalId = externalId,
                email = externalId,
                isAdmin = isAdmin
            )
        )

    private fun createNamespace(name: String = "test-namespace"): Namespace =
        namespaceRepository.save(
            Namespace(metadata = EntityMetadata(), name = name)
        )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "create ADMIN relation between user and namespace in Neo4j" {
            val user = createUser()
            val namespace = createNamespace()

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            val hasAdmin = permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            hasAdmin.shouldBeTrue()
        }

        "create MEMBER relation between user and namespace in Neo4j" {
            val user = createUser()
            val namespace = createNamespace()

            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            val hasMemberOrAdmin = permissionNodeRepository.hasMemberOrAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            hasMemberOrAdmin.shouldBeTrue()

            // MEMBER should NOT grant ADMIN
            val hasAdmin = permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            hasAdmin.shouldBeFalse()
        }

        "delete ADMIN relation removes the relationship" {
            val user = createUser()
            val namespace = createNamespace()

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Verify it exists
            permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            ).shouldBeTrue()

            // Delete it
            permissionNodeRepository.deleteAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Verify it's gone
            permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            ).shouldBeFalse()
        }

        "delete MEMBER relation removes the relationship" {
            val user = createUser()
            val namespace = createNamespace()

            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Delete it
            permissionNodeRepository.deleteMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Verify it's gone
            permissionNodeRepository.hasMemberOrAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            ).shouldBeFalse()
        }

        "hasAdminPermission returns false when no relation exists" {
            val user = createUser()
            val namespace = createNamespace()

            permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            ).shouldBeFalse()
        }

        "hasMemberOrAdminPermission returns true for ADMIN relation" {
            val user = createUser()
            val namespace = createNamespace()

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // ADMIN should satisfy member-or-admin check
            permissionNodeRepository.hasMemberOrAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            ).shouldBeTrue()
        }

        "findUsersWithAdminPermission returns correct users" {
            val admin = createUser("admin@example.com")
            val member = createUser("member@example.com")
            val namespace = createNamespace()

            permissionNodeRepository.createAdminPermission(
                userId = admin.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            permissionNodeRepository.createMemberPermission(
                userId = member.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            val adminUsers = permissionNodeRepository.findUsersWithAdminPermission(
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            adminUsers shouldHaveSize 1
            adminUsers shouldContain admin.id.toString()
            adminUsers shouldNotContain member.id.toString()
        }

        "findUsersWithAnyPermission returns all users with relations" {
            val admin = createUser("admin@example.com")
            val member = createUser("member@example.com")
            val noRelation = createUser("none@example.com")
            val namespace = createNamespace()

            permissionNodeRepository.createAdminPermission(
                userId = admin.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            permissionNodeRepository.createMemberPermission(
                userId = member.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            val allUsers = permissionNodeRepository.findUsersWithAnyPermission(
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            allUsers shouldHaveSize 2
            allUsers shouldContain admin.id.toString()
            allUsers shouldContain member.id.toString()
            allUsers shouldNotContain noRelation.id.toString()
        }

        "creating duplicate relation is idempotent (MERGE)" {
            val user = createUser()
            val namespace = createNamespace()

            // Create same relation twice
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Should still only have one relationship
            val admins = permissionNodeRepository.findUsersWithAdminPermission(
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            admins shouldHaveSize 1
        }
    }
}
