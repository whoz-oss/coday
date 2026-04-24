package io.whozoss.agentos.permissions

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSpec
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.runBlocking
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Integration tests for Story 1.4: Transitive Permission Evaluation.
 *
 * Verifies that ADMIN/MEMBER permissions on a namespace correctly propagate
 * to child entities (Cases) through the real Neo4j graph traversal.
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
class Neo4jTransitivePermissionsSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
            Neo4jContainerSpec.registerProperties(registry)
    }

    @Autowired
    lateinit var permissionService: PermissionService

    @Autowired
    lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var namespaceRepository: NamespaceRepository

    @Autowired
    lateinit var caseRepository: CaseRepository

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

    private fun createCase(namespaceId: java.util.UUID): Case =
        caseRepository.save(
            Case(metadata = EntityMetadata(), namespaceId = namespaceId)
        )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "ADMIN on namespace grants ADMIN (all actions) on child Case via transitive traversal" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant ADMIN on namespace
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Verify transitive READ on child Case
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.READ
                )
            }.shouldBeTrue()

            // Verify transitive WRITE on child Case
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.WRITE
                )
            }.shouldBeTrue()

            // Verify transitive DELETE on child Case
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.DELETE
                )
            }.shouldBeTrue()
        }

        "MEMBER on namespace grants READ on child Case" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant MEMBER on namespace
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // MEMBER should grant READ
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.READ
                )
            }.shouldBeTrue()
        }

        "MEMBER on namespace denies WRITE on child Case" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant MEMBER on namespace
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // MEMBER should NOT grant WRITE
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.WRITE
                )
            }.shouldBeFalse()
        }

        "MEMBER on namespace denies DELETE on child Case" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant MEMBER on namespace
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // MEMBER should NOT grant DELETE
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.DELETE
                )
            }.shouldBeFalse()
        }

        "direct permission takes precedence over transitive permission" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant MEMBER on namespace (READ only transitively)
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Grant direct ADMIN on the Case
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case"
            )

            // Direct ADMIN should grant WRITE even though namespace is only MEMBER
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.WRITE
                )
            }.shouldBeTrue()
        }

        "no permission on namespace denies all actions on child Case" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No permission granted - should deny all actions (fail-closed)
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.READ
                )
            }.shouldBeFalse()

            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.WRITE
                )
            }.shouldBeFalse()

            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Case", case.id.toString(), Action.DELETE
                )
            }.shouldBeFalse()
        }

        "super-admin bypass works with real Neo4j" {
            val superAdmin = createUser("admin@example.com", isAdmin = true)
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No permission relations - super-admin should bypass all checks
            runBlocking {
                permissionService.hasPermission(
                    superAdmin.id.toString(), "Case", case.id.toString(), Action.READ
                )
            }.shouldBeTrue()

            runBlocking {
                permissionService.hasPermission(
                    superAdmin.id.toString(), "Case", case.id.toString(), Action.WRITE
                )
            }.shouldBeTrue()

            runBlocking {
                permissionService.hasPermission(
                    superAdmin.id.toString(), "Case", case.id.toString(), Action.DELETE
                )
            }.shouldBeTrue()
        }

        "hasPermission returns false when no relation exists (fail-closed)" {
            val user = createUser()
            val namespace = createNamespace()

            // No relation exists at all
            runBlocking {
                permissionService.hasPermission(
                    user.id.toString(), "Namespace", namespace.id.toString(), Action.READ
                )
            }.shouldBeFalse()
        }
    }
}
