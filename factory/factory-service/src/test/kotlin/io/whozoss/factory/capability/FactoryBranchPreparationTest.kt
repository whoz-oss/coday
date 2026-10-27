package io.whozoss.factory.capability

import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.EnvironmentGitFacts
import io.whozoss.factory.environment.port.EnvironmentGitInspection
import io.whozoss.factory.environment.port.EnvironmentGitInspectionResult
import io.whozoss.factory.environment.port.EnvironmentGitProvider
import io.whozoss.factory.environment.port.PrepareEnvironmentBranchCommand
import io.whozoss.factory.environment.port.PrepareEnvironmentBranchResult
import io.whozoss.factory.persistence.TenantScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FactoryBranchPreparationTest {
    private val scope = TenantScope("org", "workstream")
    private val repository = mockk<WorkEnvironmentRepository>()

    @Test
    fun `missing binding refuses without invoking provider`() {
        every { repository.findLatestByWorkflowId(scope, "wf-1") } returns null
        val result = FactoryBranchPreparation(repository, emptyList()).prepare(scope, "wf-1", "factory/WZ-33157")
        assertEquals("binding_missing", assertIs<BranchPreparationResult.Refused>(result).facts.failureStage)
    }

    @Test
    fun `unsupported provider refuses fail closed`() {
        every { repository.findLatestByWorkflowId(scope, "wf-1") } returns environment()
        val result = FactoryBranchPreparation(repository, emptyList()).prepare(scope, "wf-1", "factory/WZ-33157")
        assertEquals("provider_unsupported", assertIs<BranchPreparationResult.Refused>(result).facts.failureStage)
    }

    @Test
    fun `inspection failure is returned without prepare`() {
        val environment = environment()
        every { repository.findLatestByWorkflowId(scope, "wf-1") } returns environment
        val provider = FakeProvider(
            inspectionResult = EnvironmentGitInspectionResult.Refused(facts("inspection_failed")),
        )
        val result = FactoryBranchPreparation(repository, listOf(provider)).prepare(scope, "wf-1", "factory/WZ-33157")
        assertEquals("inspection_failed", assertIs<BranchPreparationResult.Refused>(result).facts.failureStage)
        assertEquals(0, provider.prepareCalls)
    }

    @Test
    fun `inspection snapshot is relayed path free to provider and facts are preserved`() {
        val environment = environment()
        every { repository.findLatestByWorkflowId(scope, "wf-1") } returns environment
        val inspection = inspection()
        val provider = FakeProvider(EnvironmentGitInspectionResult.Inspected(inspection))
        val result = FactoryBranchPreparation(repository, listOf(provider)).prepare(scope, "wf-1", "factory/WZ-33157")
        val prepared = assertIs<BranchPreparationResult.Prepared>(result)
        val command = assertNotNull(provider.lastCommand)
        assertEquals(environment.environmentId, command.environmentRef)
        assertEquals(environment.revision, command.expectedEnvironmentRevision)
        assertEquals(inspection.head, command.expectedHead)
        assertEquals(inspection.baselineHash, command.expectedBaselineHash)
        assertEquals(inspection.inspectionToken, command.inspectionToken)
        assertEquals("factory/WZ-33157", command.branchName)
        assertEquals("local-worktree", prepared.facts.providerId)
        assertEquals(environment.environmentId, prepared.facts.environmentId)
    }

    @Test
    fun `provider inspection drift refusal remains a failed result`() {
        val environment = environment()
        every { repository.findLatestByWorkflowId(scope, "wf-1") } returns environment
        val provider = FakeProvider(
            inspectionResult = EnvironmentGitInspectionResult.Inspected(inspection()),
            prepareResult = PrepareEnvironmentBranchResult.Refused(facts("inspection_drift")),
        )
        val result = FactoryBranchPreparation(repository, listOf(provider)).prepare(scope, "wf-1", "factory/wf-1")
        assertEquals("inspection_drift", assertIs<BranchPreparationResult.Refused>(result).facts.failureStage)
    }

    private fun environment() = WorkEnvironment(
        organizationId = scope.organizationId, workstreamId = scope.workstreamId,
        environmentId = "env-1", workUnitId = "wu-1", workflowId = "wf-1", namespaceId = "ns-1",
        repoRoot = "/not-observed-by-control-plane", integrationBranch = "main", branch = "initial",
        worktreePath = "/provider-owned", createdBy = "test", revision = 3,
    )

    private fun inspection() = EnvironmentGitInspection(
        providerId = "local-worktree", environmentId = "env-1", environmentRevision = 3,
        head = "a".repeat(40), currentBranch = "main", baselineHash = "sha256:baseline",
        baselinePathCount = 2, baselinePaths = listOf("a", "b"), dirty = true, inspectionToken = "opaque-token",
    )

    private fun facts(stage: String) = EnvironmentGitFacts(
        providerId = "local-worktree", environmentId = "env-1", environmentRevision = 3,
        branch = "factory/WZ-33157", previousBranch = "main", startingHead = "a".repeat(40),
        baselineHash = "sha256:baseline", baselinePathCount = 2, baselinePaths = listOf("a", "b"), dirty = true,
        mutationApplied = stage == "branch_created_or_switched", failureStage = stage,
    )

    private class FakeProvider(
        private val inspectionResult: EnvironmentGitInspectionResult,
        private val prepareResult: PrepareEnvironmentBranchResult? = null,
    ) : EnvironmentGitProvider {
        override val providerId = "local-worktree"
        var prepareCalls = 0
        var lastCommand: PrepareEnvironmentBranchCommand? = null
        override fun supports(environment: WorkEnvironment): Boolean = true
        override fun inspect(environment: WorkEnvironment, expectedEnvironmentRevision: Int) = inspectionResult
        override fun prepareBranch(environment: WorkEnvironment, command: PrepareEnvironmentBranchCommand): PrepareEnvironmentBranchResult {
            prepareCalls++
            lastCommand = command
            return prepareResult ?: PrepareEnvironmentBranchResult.Prepared(
                EnvironmentGitFacts(providerId, environment.environmentId, environment.revision, command.branchName, "main",
                    command.expectedHead, command.expectedBaselineHash, 2, listOf("a", "b"), true, true,
                    "branch_created_or_switched"),
            )
        }
    }
}
