package io.whozoss.agentos.casePlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventServiceImpl
import io.whozoss.agentos.caseEvent.InMemoryCaseEventRepository
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.InMemoryCaseRepository
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Unit tests for the [CasePluginConfiguration] loader lambda.
 *
 * Instantiates [CasePluginConfiguration] directly (no Spring context), calls
 * [CasePluginConfiguration.caseToolPlugin] to get the real [CaseToolPlugin], then
 * invokes [CaseToolPlugin.provideTools] with a [ToolContext] carrying a specific
 * [userId]. The produced [ReadCaseTool] is executed to observe the loader's behaviour.
 *
 * This approach tests the **actual** lambda body from [CasePluginConfiguration],
 * not a hand-written copy of it, so any change to the configuration that removes
 * or weakens a guard will be caught here.
 *
 * Three-step guard under test:
 *   1. case not found → NOT_FOUND
 *   2. namespace mismatch → NOT_FOUND
 *   3. userId non-null + permission denied → NOT_FOUND
 *   4. userId non-null + permission granted → success
 *   5. userId null → permission check skipped, success
 */
class CasePluginConfigurationUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val otherNamespaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()

    fun buildCase(id: UUID = UUID.randomUUID(), ns: UUID = namespaceId) = Case(
        metadata = EntityMetadata(id = id),
        namespaceId = ns,
        status = CaseStatus.IDLE,
        title = "Test case",
    )

    /**
     * Builds a [ReadCaseTool] from [CasePluginConfiguration] wired with in-memory
     * repositories and the given [permissionService]. The [userId] is passed as the
     * [ToolContext.userId] so the loader's permission check sees the correct identity.
     */
    fun buildTool(
        caseRepo: InMemoryCaseRepository,
        permissionService: PermissionService,
        userId: UUID?,
    ): ReadCaseTool {
        val eventService = CaseEventServiceImpl(InMemoryCaseEventRepository())
        val config = CasePluginConfiguration(caseRepo, eventService, permissionService)
        val plugin = config.caseToolPlugin() as CaseToolPlugin
        val context = ToolContext(
            namespaceId = namespaceId,
            userId = userId,
            userExternalId = null,
            caseEvents = emptyList(),
        )
        return plugin.provideTools(config = null, context = context).first() as ReadCaseTool
    }

    // -------------------------------------------------------------------------
    // Case not found
    // -------------------------------------------------------------------------

    "returns NOT_FOUND when case does not exist" {
        val permissionService = mockk<PermissionService>(relaxed = true)
        val tool = buildTool(InMemoryCaseRepository(), permissionService, userId)

        val result = tool.execute(ReadCaseTool.Input(caseId = UUID.randomUUID().toString()), ToolContext(
            namespaceId = namespaceId, userId = userId, userExternalId = null, caseEvents = emptyList(),
        ))

        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Namespace mismatch
    // -------------------------------------------------------------------------

    "returns NOT_FOUND when case belongs to a different namespace" {
        val caseRepo = InMemoryCaseRepository()
        val case = buildCase(ns = otherNamespaceId)
        caseRepo.save(case)
        val permissionService = mockk<PermissionService>(relaxed = true)
        val tool = buildTool(caseRepo, permissionService, userId)

        val result = tool.execute(ReadCaseTool.Input(caseId = case.id.toString()), ToolContext(
            namespaceId = namespaceId, userId = userId, userExternalId = null, caseEvents = emptyList(),
        ))

        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
        // Permission must never be consulted when the namespace guard already rejects
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Permission denied
    // -------------------------------------------------------------------------

    "returns NOT_FOUND when userId is non-null and permission is denied" {
        val caseRepo = InMemoryCaseRepository()
        val case = buildCase()
        caseRepo.save(case)
        val permissionService = mockk<PermissionService> {
            every { hasPermission(any(), EntityType.CASE, any(), Action.READ) } returns false
        }
        val tool = buildTool(caseRepo, permissionService, userId)

        val result = tool.execute(ReadCaseTool.Input(caseId = case.id.toString()), ToolContext(
            namespaceId = namespaceId, userId = userId, userExternalId = null, caseEvents = emptyList(),
        ))

        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
        verify(exactly = 1) {
            permissionService.hasPermission(userId.toString(), EntityType.CASE, case.id.toString(), Action.READ)
        }
    }

    // -------------------------------------------------------------------------
    // Permission granted
    // -------------------------------------------------------------------------

    "returns success when userId is non-null and permission is granted" {
        val caseRepo = InMemoryCaseRepository()
        val case = buildCase()
        caseRepo.save(case)
        val permissionService = mockk<PermissionService> {
            every { hasPermission(any(), EntityType.CASE, any(), Action.READ) } returns true
        }
        val tool = buildTool(caseRepo, permissionService, userId)

        val result = tool.execute(ReadCaseTool.Input(caseId = case.id.toString()), ToolContext(
            namespaceId = namespaceId, userId = userId, userExternalId = null, caseEvents = emptyList(),
        ))

        result.success shouldBe true
        verify(exactly = 1) {
            permissionService.hasPermission(userId.toString(), EntityType.CASE, case.id.toString(), Action.READ)
        }
    }

    // -------------------------------------------------------------------------
    // Anonymous / system call (userId null)
    // -------------------------------------------------------------------------

    "returns NOT_FOUND without permission check when userId is null" {
        val caseRepo = InMemoryCaseRepository()
        val case = buildCase()
        caseRepo.save(case)
        val permissionService = mockk<PermissionService>(relaxed = true)
        val tool = buildTool(caseRepo, permissionService, userId = null)

        val result = tool.execute(ReadCaseTool.Input(caseId = case.id.toString()), ToolContext(
            namespaceId = namespaceId, userId = null, userExternalId = null, caseEvents = emptyList(),
        ))

        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
        verify(exactly = 0) { permissionService.hasPermission(any(), any(), any(), any()) }
    }
})
