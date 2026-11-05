package io.whozoss.factory.worktree

import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.*
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.EnvironmentGitFacts
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.*
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ParentRunCodeOccupancyIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var workflows: WorkflowRepository
    @Autowired lateinit var evidence: WorkflowEvidenceRepository
    @Autowired lateinit var interactions: HumanInteractionRepository
    @Autowired lateinit var attemptRows: AgentStepAttemptRepository
    @Autowired lateinit var durableAttempts: DurableAgentAttemptService
    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var runs: RunRepository
    @Autowired lateinit var occupancyRepository: Neo4jWorktreeExecutionOccupancyRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @TempDir lateinit var root: Path
    private val namespace="1f8a2c10-9e3b-4c5a-8a11-codeoccupancy"

    @Test fun `existing agent occupation refuses code before marker side effect`() {
        val fixture=fixture("occupied","touch '${root.resolve("marker")}'",1000)
        fixture.occupancy.acquireForWorkflow(scope,fixture.runId,"agent","attempt-active",root)
        val result=fixture.execute()
        assertThat(result.outcome).isInstanceOf(CapabilityOutcome.CodeRefused::class.java)
        assertThat((result.outcome as CapabilityOutcome.CodeRefused).code).isEqualTo("WORKTREE_OCCUPIED")
        assertThat(Files.exists(root.resolve("marker"))).isFalse()
    }

    @Test fun `normal zero and nonzero direct exits release occupation`() {
        listOf("pass" to "exit 0","fail" to "exit 7").forEach { (id,command) ->
            val fixture=fixture(id,command,1000)
            val outcome=fixture.execute().outcome as CapabilityOutcome.CodeExecuted
            assertThat(outcome.timedOut).isFalse()
            assertThat(occupancyRepository.find(LocalWorktreeResourceIdentityResolver().resolve(root))!!.status.name).isEqualTo("RELEASED")
        }
    }

    @Test fun `timeout retains occupation`() {
        val fixture=fixture("timeout","sleep 2",50)
        val outcome=fixture.execute().outcome as CapabilityOutcome.CodeExecuted
        assertThat(outcome.timedOut).isTrue()
        assertThat(occupancyRepository.find(LocalWorktreeResourceIdentityResolver().resolve(root))!!.status.name).isEqualTo("ACTIVE")
    }

    @Test fun `manifest refusal before spawn releases occupation`() {
        val fixture=fixture("refused","exit 0",1000)
        Files.delete(root.resolve("factory/verification.json"))

        val outcome=fixture.execute().outcome as CapabilityOutcome.CodeRefused

        assertThat(outcome.code).isNotEqualTo("WORKTREE_OCCUPIED")
        assertThat(occupancyRepository.find(LocalWorktreeResourceIdentityResolver().resolve(root))!!.status.name).isEqualTo("RELEASED")
    }

    @Test fun `completed branch preparation retains occupation without provider quiescence contract`() {
        val prepared = EnvironmentGitFacts(
            providerId = "test-provider",
            environmentId = "env-branch-prepared",
            environmentRevision = 1,
            branch = "factory/test",
            previousBranch = "main",
            startingHead = "abc",
            baselineHash = "baseline",
            mutationApplied = true,
            failureStage = "branch_created_or_switched",
        )
        val branchPreparation = mockk<FactoryBranchPreparation>()
        every { branchPreparation.prepare(any(), any(), any()) } returns BranchPreparationResult.Prepared(prepared)
        val fixture=fixture("branch-prepared","exit 0",1000, CapabilityResolver(branchPreparation = branchPreparation))

        val outcome=fixture.execute(WorkflowStepDefinition("prepare","Prepare",WorkflowStepResponsibility(ResponsibilityKind.CODE,PREPARE_WORK_BRANCH),emptyList())).outcome

        assertThat(outcome).isInstanceOf(CapabilityOutcome.BranchPrepared::class.java)
        assertThat(occupancyRepository.find(LocalWorktreeResourceIdentityResolver().resolve(root))!!.status.name).isEqualTo("ACTIVE")
    }

    @Test fun `branch refusal reporting no mutation still retains occupation without provider quiescence contract`() {
        val branchPreparation = mockk<FactoryBranchPreparation>()
        every { branchPreparation.prepare(any(), any(), any()) } returns BranchPreparationResult.Refused(
            EnvironmentGitFacts(
                providerId = "test-provider",
                environmentId = "env-branch-refused",
                environmentRevision = 1,
                branch = "factory/test",
                previousBranch = "main",
                startingHead = "abc",
                baselineHash = "baseline",
                mutationApplied = false,
                failureStage = "not_started",
                message = "uncertain after mutation",
            ),
        )
        val fixture=fixture("branch-refused","exit 0",1000, CapabilityResolver(branchPreparation = branchPreparation))

        val outcome=fixture.execute(WorkflowStepDefinition("prepare","Prepare",WorkflowStepResponsibility(ResponsibilityKind.CODE,PREPARE_WORK_BRANCH),emptyList())).outcome as CapabilityOutcome.CodeRefused

        assertThat(outcome.mutationApplied).isFalse()
        assertThat(occupancyRepository.find(LocalWorktreeResourceIdentityResolver().resolve(root))!!.status.name).isEqualTo("ACTIVE")
    }

    @Test fun `resolver exception retains occupation fail closed`() {
        val throwingRegistry = mockk<FactoryFrontVerificationRegistry>()
        every { throwingRegistry.resolve(any(), any(), any()) } throws IllegalStateException("injected resolver failure")
        val fixture=fixture("resolver-exception","exit 0",1000, CapabilityResolver(frontVerificationRegistry = throwingRegistry))
        val step = WorkflowStepDefinition("verify","Verify",WorkflowStepResponsibility(ResponsibilityKind.CODE,"frontend-build"),emptyList())

        org.assertj.core.api.Assertions.assertThatThrownBy { fixture.execute(step) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("injected resolver failure")
        assertThat(occupancyRepository.find(LocalWorktreeResourceIdentityResolver().resolve(root))!!.status.name).isEqualTo("ACTIVE")
    }

    private inner class Fixture(val workflowId:String,val runId:String,val occupancy:WorktreeExecutionOccupancyService,val service:CapabilityExecutionService) {
        fun execute(step: WorkflowStepDefinition = WorkflowStepDefinition("verify","Verify",WorkflowStepResponsibility(ResponsibilityKind.CODE,"check"),emptyList()))=
            service.resolveAndRecord(scope,namespace,workflowId,step,root)
    }

    private fun fixture(id:String,command:String,timeout:Long,resolver: CapabilityResolver = CapabilityResolver()):Fixture {
        Files.createDirectories(root.resolve("factory"))
        Files.writeString(root.resolve("factory/verification.json"),"""{"schemaVersion":"1","verifications":{"check":{"command":"$command","timeoutMs":$timeout}}}""",StandardCharsets.UTF_8)
        val runId="run-code-$id"; val workflowId="fragment-code-$id"; val rootCase="root-code-$id"
        runs.insert(scope,RunRecord(namespace,runId,"test","1",runId,rootCaseId=rootCase))
        workflows.insertInstance(scope,WorkflowInstanceRecord(namespace,workflowId,1,"active","hash-$id",mapOf("runId" to runId,"workflowType" to "test"),mapOf("steps" to emptyList<Any>()),rootCaseId=rootCase))
        environments.insert(scope,WorkEnvironment(scope.organizationId,scope.workstreamId,"env-$id","wu-$id",runId,namespace,rootCase,root.toString(),"main","branch-$id",root.toString(),lifecycleState=WorkEnvironmentState.READY,createdBy="test"))
        val occupancy=WorktreeExecutionOccupancyService(environments,occupancyRepository, transactionManager = transactionManager)
        val service=CapabilityExecutionService(resolver,workflows,evidence,interactions,attemptRows,durableAgentAttemptService=durableAttempts,agentOsExecutionAdapter=mockk<AgentOsExecutionAdapter>(relaxed=true),transactionManager=transactionManager,workEnvironmentRepository=environments,worktreeExecutionOccupancyService=occupancy)
        return Fixture(workflowId,runId,occupancy,service)
    }
}
