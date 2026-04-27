package io.whozoss.agentos.permissions

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
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
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Integration tests for Transitive Permission Evaluation.
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
    lateinit var agentConfigRepository: AgentConfigRepository

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
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.READ
            ).shouldBeTrue()

            // Verify transitive WRITE on child Case
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.WRITE
            ).shouldBeTrue()

            // Verify transitive DELETE on child Case
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.DELETE
            ).shouldBeTrue()
        }

        /**
         * FR15: a namespace MEMBER must NOT gain transitive READ on
         * child Cases. Each case is owner-private — only the creator (direct
         * ADMIN via  auto-grant) or the namespace ADMIN can access it.
         * Test previously asserted the opposite when the bug was present.
         */
        "MEMBER on namespace does NOT grant READ on child Case" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Grant MEMBER on namespace only
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // Before this returned true (bug). It must now be false.
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.READ
            ).shouldBeFalse()
        }

        "MEMBER with direct ADMIN on a case retains full access" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // Only namespace MEMBER — triggers the FR15 block on transitivity
            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )
            // Plus direct ADMIN on the case (simulates the auto-grant)
            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case"
            )

            // Direct relation grants full access regardless of the transitive rule
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.READ
            ).shouldBeTrue()
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.WRITE
            ).shouldBeTrue()
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.DELETE
            ).shouldBeTrue()
        }

        /**
         * Anti-regression: the FR15 rule must apply ONLY to Case. Shared
         * child entities (AgentConfig, IntegrationConfig, AiProvider, AiModel)
         * keep the MEMBER → transitive READ behaviour required by FR21/FR27/
         * FR32/FR35. We simulate an "AgentConfig" node by creating a
         * non-Case entity with a BELONGS_TO edge to the namespace.
         */
        /**
         * Soft-deleting a case must NOT remove its [:ADMIN]
         * relations — they survive for audit purposes. The repository's delete
         * implementation merely sets `removed = true` on the CaseNode and must
         * leave every permission edge intact.
         */
        "soft-deleting a case preserves direct ADMIN relation for audit" {
            val user = createUser("owner@example.com")
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            permissionNodeRepository.createAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case"
            )
            // Sanity check: ADMIN relation exists before the delete
            permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case"
            ).shouldBeTrue()

            // Soft-delete the case via the repository
            val deleted = caseRepository.delete(case.id)
            deleted.shouldBeTrue()

            // The case is no longer findable (filtered by `removed != true`)
            caseRepository.findByIds(listOf(case.id)).shouldBeEmpty()

            // But the direct [:ADMIN] relation in Neo4j is preserved for audit
            permissionNodeRepository.hasAdminPermission(
                userId = user.id.toString(),
                entityId = case.id.toString(),
                entityLabel = "Case"
            ).shouldBeTrue()
        }

        /**
         * : AgentConfig relies on transitive permissions via the
         * `[:BELONGS_TO]` relation. Unlike Case (owner-private), AgentConfig is
         * a shared configuration — namespace MEMBERs must gain transitive READ
         * on all configs (FR21), and namespace ADMINs must gain transitive
         * WRITE/DELETE (FR17/18/19).
         */
        "AgentConfig gets transitive permissions from its parent namespace" {
            val member = createUser("member@example.com")
            val admin = createUser("admin@example.com")
            val namespace = createNamespace()
            val config = agentConfigRepository.save(
                AgentConfig(
                    metadata = EntityMetadata(),
                    namespaceId = namespace.id,
                    name = "shared-agent",
                ),
            )

            // member gets MEMBER on namespace → should READ transitively but NOT WRITE/DELETE
            permissionNodeRepository.createMemberPermission(
                userId = member.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace",
            )
            permissionService.hasPermission(
                member.id.toString(), "AgentConfig", config.metadata.id.toString(), Action.READ,
            ).shouldBeTrue()
            permissionService.hasPermission(
                member.id.toString(), "AgentConfig", config.metadata.id.toString(), Action.WRITE,
            ).shouldBeFalse()
            permissionService.hasPermission(
                member.id.toString(), "AgentConfig", config.metadata.id.toString(), Action.DELETE,
            ).shouldBeFalse()

            // admin gets ADMIN on namespace → full transitive CRUD
            permissionNodeRepository.createAdminPermission(
                userId = admin.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace",
            )
            permissionService.hasPermission(
                admin.id.toString(), "AgentConfig", config.metadata.id.toString(), Action.READ,
            ).shouldBeTrue()
            permissionService.hasPermission(
                admin.id.toString(), "AgentConfig", config.metadata.id.toString(), Action.WRITE,
            ).shouldBeTrue()
            permissionService.hasPermission(
                admin.id.toString(), "AgentConfig", config.metadata.id.toString(), Action.DELETE,
            ).shouldBeTrue()
        }

        "namespace MEMBER still grants transitive READ on non-Case shared entities" {
            val user = createUser()
            val namespace = createNamespace()
            // Create an AgentConfig-like node directly in Neo4j with a BELONGS_TO edge
            val agentConfigId = java.util.UUID.randomUUID().toString()
            driver.session().use { session ->
                session.run(
                    "MATCH (ns:Namespace {id: \$nsId}) " +
                        "CREATE (e:AgentConfig {id: \$id, removed: false})-[:BELONGS_TO]->(ns)",
                    mapOf("id" to agentConfigId, "nsId" to namespace.id.toString()),
                )
            }

            permissionNodeRepository.createMemberPermission(
                userId = user.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace"
            )

            // MEMBER transitivity DOES apply to AgentConfig (FR21 legitimate)
            permissionService.hasPermission(
                user.id.toString(), "AgentConfig", agentConfigId, Action.READ
            ).shouldBeTrue()
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
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.WRITE
            ).shouldBeFalse()
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
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.DELETE
            ).shouldBeFalse()
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
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.WRITE
            ).shouldBeTrue()
        }

        "no permission on namespace denies all actions on child Case" {
            val user = createUser()
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No permission granted - should deny all actions (fail-closed)
            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.READ
            ).shouldBeFalse()

            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.WRITE
            ).shouldBeFalse()

            permissionService.hasPermission(
                user.id.toString(), "Case", case.id.toString(), Action.DELETE
            ).shouldBeFalse()
        }

        "super-admin bypass works with real Neo4j" {
            val superAdmin = createUser("admin@example.com", isAdmin = true)
            val namespace = createNamespace()
            val case = createCase(namespace.id)

            // No permission relations - super-admin should bypass all checks
            permissionService.hasPermission(
                superAdmin.id.toString(), "Case", case.id.toString(), Action.READ
            ).shouldBeTrue()

            permissionService.hasPermission(
                superAdmin.id.toString(), "Case", case.id.toString(), Action.WRITE
            ).shouldBeTrue()

            permissionService.hasPermission(
                superAdmin.id.toString(), "Case", case.id.toString(), Action.DELETE
            ).shouldBeTrue()
        }

        "hasPermission returns false when no relation exists (fail-closed)" {
            val user = createUser()
            val namespace = createNamespace()

            // No relation exists at all
            permissionService.hasPermission(
                user.id.toString(), "Namespace", namespace.id.toString(), Action.READ
            ).shouldBeFalse()
        }

        /**
         * A namespace ADMIN transitively has ADMIN on cases that
         * were created — and auto-granted — to a different user. Proves end-to-end
         * that `findByParent` + transitive `hasPermission` surfaces all cases in the
         * namespace to a namespace admin, not just those they created themselves.
         */
        "namespace ADMIN has transitive ADMIN on cases created by other users" {
            val adminUser = createUser("admin@example.com")
            val creatorUser = createUser("creator@example.com")
            val namespace = createNamespace()
            val case1 = createCase(namespace.id)
            val case2 = createCase(namespace.id)

            // adminUser holds [:ADMIN] on the namespace
            permissionNodeRepository.createAdminPermission(
                userId = adminUser.id.toString(),
                entityId = namespace.id.toString(),
                entityLabel = "Namespace",
            )
            // creatorUser holds direct [:ADMIN] on their two cases
            // (mirrors  auto-grant flow)
            permissionNodeRepository.createAdminPermission(
                userId = creatorUser.id.toString(),
                entityId = case1.id.toString(),
                entityLabel = "Case",
            )
            permissionNodeRepository.createAdminPermission(
                userId = creatorUser.id.toString(),
                entityId = case2.id.toString(),
                entityLabel = "Case",
            )

            // adminUser has NO direct relation on either case, yet should have
            // full ADMIN privileges via transitive namespace ADMIN.
            listOf(case1, case2).forEach { case ->
                permissionService.hasPermission(
                    adminUser.id.toString(), "Case", case.id.toString(), Action.READ,
                ).shouldBeTrue()
                permissionService.hasPermission(
                    adminUser.id.toString(), "Case", case.id.toString(), Action.WRITE,
                ).shouldBeTrue()
                permissionService.hasPermission(
                    adminUser.id.toString(), "Case", case.id.toString(), Action.DELETE,
                ).shouldBeTrue()
            }

            // Service layer: findByParent returns every case in the namespace,
            // letting the controller's short-circuit () skip per-case
            // filtering for a namespace admin.
            caseRepository.findByParent(namespace.id) shouldHaveSize 2
        }
    }
}
