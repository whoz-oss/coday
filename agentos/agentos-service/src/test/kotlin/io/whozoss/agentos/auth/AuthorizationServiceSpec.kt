package io.whozoss.agentos.auth

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.auth.AccessDecision
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.auth.PermissionEvaluator
import io.whozoss.agentos.sdk.auth.ToolCategory
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityConfigProperties
import io.whozoss.agentos.security.SecurityMode
import org.pf4j.PluginManager
import java.util.UUID

class AuthorizationServiceSpec : StringSpec() {

    // Use valid UUIDs as String IDs (codebase convention: IDs are UUID strings)
    private val userId = UUID.randomUUID().toString()
    private val namespaceId = UUID.randomUUID().toString()
    private val caseId = UUID.randomUUID().toString()

    private fun buildService(
        roleRepository: RoleRepository = mockk(),
        pluginManager: PluginManager = mockk {
            every { getExtensions(PermissionEvaluator::class.java) } returns emptyList()
        },
        permissive: Boolean = true,
        namespaceService: NamespaceService = mockk {
            every { findAll() } returns emptyList()
        },
        caseService: CaseService = mockk {
            every { findByParent(any()) } returns emptyList()
        },
    ): AuthorizationServiceImpl = AuthorizationServiceImpl(
        roleRepository,
        pluginManager,
        SecurityConfigProperties(mode = SecurityMode.LOCAL, permissive = permissive),
        namespaceService,
        caseService,
    )

    init {
        // =====================================================================
        // isRoot bypass — namespace
        // =====================================================================

        "isRoot user bypasses namespace access check" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true

            val service = buildService(roleRepository = repo)
            shouldNotThrowAny { service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.OWNER) }
        }

        // =====================================================================
        // isRoot bypass — case
        // =====================================================================

        "isRoot user bypasses case access check" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true

            val service = buildService(roleRepository = repo)
            shouldNotThrowAny { service.requireCaseAccess(userId, caseId, Operation.DELETE) }
        }

        // =====================================================================
        // Hierarchical resolution — ADMIN satisfies MEMBER
        // =====================================================================

        "ADMIN satisfies MEMBER namespace access" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceRole(userId, namespaceId) } returns NamespaceRole.ADMIN

            val service = buildService(roleRepository = repo)
            shouldNotThrowAny { service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.MEMBER) }
        }

        // =====================================================================
        // Hierarchical rejection — MEMBER does not satisfy ADMIN
        // =====================================================================

        "MEMBER does not satisfy ADMIN namespace access" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceRole(userId, namespaceId) } returns NamespaceRole.MEMBER

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.ADMIN)
            }
            ex.reason shouldBe "Role MEMBER does not satisfy required ADMIN"
        }

        // =====================================================================
        // Permissive mode — access granted without role
        // =====================================================================

        "permissive mode grants namespace access when no role assigned" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceRole(userId, namespaceId) } returns null

            val service = buildService(roleRepository = repo, permissive = true)
            shouldNotThrowAny { service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.MEMBER) }
        }

        "permissive mode grants case access when no role assigned" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns null

            val service = buildService(roleRepository = repo, permissive = true)
            shouldNotThrowAny { service.requireCaseAccess(userId, caseId, Operation.WRITE) }
        }

        // =====================================================================
        // Strict mode — access denied without role
        // =====================================================================

        "strict mode denies namespace access when no role assigned" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceRole(userId, namespaceId) } returns null

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.VIEWER)
            }
            ex.reason shouldBe "No role assigned for user $userId in namespace $namespaceId"
        }

        // =====================================================================
        // Fail-closed — RoleRepository exception → deny (strict mode)
        // =====================================================================

        "fail-closed denies access when RoleRepository throws and permissive is off" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("Neo4j unavailable")

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.VIEWER)
            }
            ex.reason shouldBe "Permission check failed: system error"
        }

        "fail-closed allows access when RoleRepository throws and permissive is on" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("Neo4j unavailable")

            val service = buildService(roleRepository = repo, permissive = true)
            shouldNotThrowAny { service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.VIEWER) }
        }

        // =====================================================================
        // Evaluator delegation
        // =====================================================================

        "delegates namespace evaluation to evaluator" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceRole(userId, namespaceId) } returns NamespaceRole.VIEWER

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.ADMIN)
            }
            ex.reason shouldBe "Role VIEWER does not satisfy required ADMIN"
        }

        "delegates case evaluation — PARTICIPANT cannot WRITE" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns CaseRole.PARTICIPANT

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireCaseAccess(userId, caseId, Operation.WRITE)
            }
            ex.reason shouldBe "CaseRole PARTICIPANT cannot perform WRITE"
        }

        "delegates case evaluation — OWNER can DELETE" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns CaseRole.OWNER

            val service = buildService(roleRepository = repo, permissive = false)
            shouldNotThrowAny { service.requireCaseAccess(userId, caseId, Operation.DELETE) }
        }

        // =====================================================================
        // Plugin override — custom evaluator via PF4J
        // =====================================================================

        "uses plugin-provided evaluator instead of built-in" {
            val customEvaluator = mockk<PermissionEvaluator>()
            every { customEvaluator.evaluateNamespaceAccess(any(), any()) } returns AccessDecision.Granted("CUSTOM")

            val pluginMgr = mockk<PluginManager>()
            every { pluginMgr.getExtensions(PermissionEvaluator::class.java) } returns listOf(customEvaluator)

            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceRole(userId, namespaceId) } returns NamespaceRole.VIEWER

            val service = buildService(roleRepository = repo, pluginManager = pluginMgr, permissive = false)
            // Custom evaluator grants access even for VIEWER requesting OWNER
            shouldNotThrowAny { service.requireNamespaceAccess(userId, namespaceId, NamespaceRole.OWNER) }
        }

        // =====================================================================
        // canExecuteTool / getAvailableTools
        // =====================================================================

        "canExecuteTool returns true for root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true

            val service = buildService(roleRepository = repo)
            service.canExecuteTool(userId, caseId, "tool", ToolCategory.ADMIN) shouldBe true
        }

        "canExecuteTool returns true for OWNER on DESTRUCTIVE" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns CaseRole.OWNER

            val service = buildService(roleRepository = repo)
            service.canExecuteTool(userId, caseId, "tool", ToolCategory.DESTRUCTIVE) shouldBe true
        }

        "canExecuteTool returns false for PARTICIPANT on DESTRUCTIVE in strict mode" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns CaseRole.PARTICIPANT

            val service = buildService(roleRepository = repo, permissive = false)
            service.canExecuteTool(userId, caseId, "tool", ToolCategory.DESTRUCTIVE) shouldBe false
        }

        "getAvailableTools returns all tools for root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true

            val allTools = mapOf("read-tool" to ToolCategory.READ_ONLY, "admin-tool" to ToolCategory.ADMIN)
            val service = buildService(roleRepository = repo)
            service.getAvailableTools(userId, caseId, allTools) shouldBe setOf("read-tool", "admin-tool")
        }

        "getAvailableTools filters by PARTICIPANT role" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns CaseRole.PARTICIPANT

            val allTools = mapOf(
                "read-tool" to ToolCategory.READ_ONLY,
                "write-tool" to ToolCategory.WRITE,
                "admin-tool" to ToolCategory.ADMIN,
            )
            val service = buildService(roleRepository = repo, permissive = false)
            service.getAvailableTools(userId, caseId, allTools) shouldBe setOf("read-tool", "write-tool")
        }

        // =====================================================================
        // isRoot / requireRoot
        // =====================================================================

        "isRoot returns true for root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true

            val service = buildService(roleRepository = repo)
            service.isRoot(userId) shouldBe true
        }

        "isRoot returns false for non-root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false

            val service = buildService(roleRepository = repo)
            service.isRoot(userId) shouldBe false
        }

        "requireRoot does not throw for root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true

            val service = buildService(roleRepository = repo)
            shouldNotThrowAny { service.requireRoot(userId) }
        }

        "requireRoot throws for non-root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false

            val service = buildService(roleRepository = repo)
            val ex = shouldThrow<AccessDeniedException> { service.requireRoot(userId) }
            ex.reason shouldBe "User $userId is not root"
        }

        // =====================================================================
        // filterAccessibleNamespaceIds / filterAccessibleCaseIds
        // =====================================================================

        "filterAccessibleNamespaceIds returns namespace IDs from repository for non-root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findNamespaceIdsForUser(userId) } returns setOf(namespaceId)

            val service = buildService(roleRepository = repo)
            service.filterAccessibleNamespaceIds(userId) shouldBe setOf(namespaceId)
        }

        "filterAccessibleNamespaceIds returns ALL namespace IDs for root user" {
            val repo = mockk<RoleRepository>()
            val allNsId = UUID.randomUUID().toString()
            every { repo.isRoot(userId) } returns true
            val nsService = mockk<NamespaceService>()
            every { nsService.findAll() } returns listOf(
                Namespace(metadata = EntityMetadata(id = UUID.fromString(namespaceId)), name = "ns-1"),
                Namespace(metadata = EntityMetadata(id = UUID.fromString(allNsId)), name = "ns-2"),
            )

            val service = buildService(roleRepository = repo, namespaceService = nsService)
            val result = service.filterAccessibleNamespaceIds(userId)
            result shouldBe setOf(namespaceId, allNsId)
        }

        "filterAccessibleNamespaceIds returns all namespaces on error in permissive mode" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("DB error")
            val nsService = mockk<NamespaceService>()
            every { nsService.findAll() } returns listOf(
                Namespace(metadata = EntityMetadata(id = UUID.fromString(namespaceId)), name = "ns-1"),
            )

            val service = buildService(roleRepository = repo, permissive = true, namespaceService = nsService)
            service.filterAccessibleNamespaceIds(userId) shouldBe setOf(namespaceId)
        }

        "filterAccessibleNamespaceIds returns empty set on error in strict mode" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("DB error")

            val service = buildService(roleRepository = repo, permissive = false)
            service.filterAccessibleNamespaceIds(userId) shouldBe emptySet()
        }

        "filterAccessibleCaseIds returns case IDs from repository for non-root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findAccessibleCaseIdsForUser(userId, namespaceId) } returns setOf(caseId)

            val service = buildService(roleRepository = repo)
            service.filterAccessibleCaseIds(userId, namespaceId) shouldBe setOf(caseId)
        }

        "filterAccessibleCaseIds returns ALL case IDs for root user" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns true
            val csService = mockk<CaseService>()
            val nsUUID = UUID.fromString(namespaceId)
            every { csService.findByParent(nsUUID) } returns listOf(
                Case(metadata = EntityMetadata(id = UUID.fromString(caseId)), namespaceId = nsUUID, title = "case-1"),
            )

            val service = buildService(roleRepository = repo, caseService = csService)
            service.filterAccessibleCaseIds(userId, namespaceId) shouldBe setOf(caseId)
        }

        "filterAccessibleCaseIds returns all cases on error in permissive mode" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("DB error")
            val csService = mockk<CaseService>()
            val nsUUID = UUID.fromString(namespaceId)
            every { csService.findByParent(nsUUID) } returns listOf(
                Case(metadata = EntityMetadata(id = UUID.fromString(caseId)), namespaceId = nsUUID, title = "case-1"),
            )

            val service = buildService(roleRepository = repo, permissive = true, caseService = csService)
            service.filterAccessibleCaseIds(userId, namespaceId) shouldBe setOf(caseId)
        }

        "filterAccessibleCaseIds returns empty set on error in strict mode" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("DB error")

            val service = buildService(roleRepository = repo, permissive = false)
            service.filterAccessibleCaseIds(userId, namespaceId) shouldBe emptySet()
        }

        // =====================================================================
        // Fail-closed — requireCaseAccess (patch #4 Story 1.3)
        // =====================================================================

        "fail-closed denies case access when RoleRepository throws and permissive is off" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("Neo4j unavailable")

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireCaseAccess(userId, caseId, Operation.READ)
            }
            ex.reason shouldBe "Permission check failed: system error"
        }

        "fail-closed allows case access when RoleRepository throws and permissive is on" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } throws RuntimeException("Neo4j unavailable")

            val service = buildService(roleRepository = repo, permissive = true)
            shouldNotThrowAny { service.requireCaseAccess(userId, caseId, Operation.READ) }
        }

        "strict mode denies case access when no role assigned" {
            val repo = mockk<RoleRepository>()
            every { repo.isRoot(userId) } returns false
            every { repo.findCaseRole(userId, caseId) } returns null

            val service = buildService(roleRepository = repo, permissive = false)
            val ex = shouldThrow<AccessDeniedException> {
                service.requireCaseAccess(userId, caseId, Operation.READ)
            }
            ex.reason shouldBe "No role assigned for user $userId in case $caseId"
        }
    }
}
