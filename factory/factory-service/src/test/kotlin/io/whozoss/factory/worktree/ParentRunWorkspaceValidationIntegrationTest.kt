package io.whozoss.factory.worktree

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.*
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.workflow.domain.*
import io.whozoss.factory.workflow.persistence.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path

class ParentRunWorkspaceValidationIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var workflows: WorkflowRepository
    @Autowired lateinit var evidence: WorkflowEvidenceRepository
    @Autowired lateinit var interactions: HumanInteractionRepository
    @Autowired lateinit var attempts: AgentStepAttemptRepository
    @Autowired lateinit var durableAttempts: DurableAgentAttemptService
    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var runs: RunRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var occupancyRepository: io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
    @TempDir lateinit var root: Path

    private val namespace = "0f8a2c10-9e3b-4c5a-8a11-workspacecheck"
    private val workflowId = "fragment-workspace-check"
    private val runId = "run-workspace-check"
    private val step = WorkflowStepDefinition("writer", "Writer", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Writer"), emptyList())

    @Test fun `matching workspace starts exactly one turn`() { fixture().run { service.resolveAndRecord(scope,namespace,workflow,step,root); verify(exactly=1) { adapter.startTurn(any<TrustedCaseBinding>(),any(),any()) } } }
    @Test fun `divergent workspace starts no turn`() {
        fixture(workspacePath=Files.createDirectory(root.resolve("other")))
            .assertRefused(AgentOsWorkspaceIdentityValidationException::class.java, "does not match")
    }
    @Test fun `nonready workspace starts no turn`() {
        fixture(id = "nonready", response=mapOf("equipped" to true,"status" to "PROVISIONING","path" to root.toString()))
            .assertRefused(AgentOsWorkspaceIdentityValidationException::class.java, "not ready")
    }

    @Test fun `remote workspace failure starts no turn`() {
        fixture(id = "remote-error-${java.util.UUID.randomUUID()}", remoteFailure=true)
            .assertRefused(AgentOsWorkspaceIdentityValidationException::class.java, "could not be read")
    }
    @Test fun `polling fallback refuses parent Run before any resolver or remote side effect`() {
        val fixture = fixture(id = "polling-fallback")
        val pollingService = CapabilityExecutionService(
            CapabilityResolver(), workflows, evidence, interactions, attempts,
            durableAgentAttemptService = durableAttempts,
            agentOsExecutionAdapter = fixture.adapter,
            transactionManager = transactionManager,
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            workEnvironmentRepository = environments,
        )

        val result = pollingService.resolveAndRecord(scope, namespace, fixture.workflow, step, root)

        assertThat(result.outcome).isInstanceOf(io.whozoss.factory.capability.CapabilityOutcome.AgentDeferred::class.java)
        assertThat((result.outcome as io.whozoss.factory.capability.CapabilityOutcome.AgentDeferred).code)
            .isEqualTo("AGENT_POLLING_PARENT_RUN_UNSUPPORTED")
        verify(exactly = 0) { fixture.adapter.createOrRecoverExecution(any<TrustedCaseBinding>(), any(), any()) }
        verify(exactly = 0) { fixture.adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `stale captured environment revision starts no turn`() {
        fixture(id = "stale", staleRevision=true)
            .assertRefused(WorktreeExecutionOccupancyResolutionException::class.java, "expected 1")
    }

    @Test fun `failed preflight stays pre-send and retry of same attempt validates then starts once`() {
        val fixture = fixture(id = "retry-preflight", mutablePreflight = true)
        assertThatThrownBy { fixture.service.resolveAndRecord(scope, namespace, fixture.workflow, step, root) }
            .isInstanceOf(AgentOsWorkspaceIdentityValidationException::class.java)
        val attemptId = CapabilityExecutionService.stableAttemptId(fixture.workflow, step.id)
        val afterFailure = durableAttempts.find(scope, namespace, fixture.workflow, step.id, attemptId)!!
        assertThat(afterFailure.status).isEqualTo(io.whozoss.factory.agentattempt.domain.AgentAttemptStatus.CLAIMING)
        verify(exactly = 0) { fixture.adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }

        fixture.preflightReady!!.set(true)
        val retried = fixture.service.resolveAndRecord(scope, namespace, fixture.workflow, step, root)
        assertThat(retried.outcome).isInstanceOf(io.whozoss.factory.capability.CapabilityOutcome.AgentCompleted::class.java)
        assertThat(retried.attemptId).isEqualTo(attemptId)
        verify(exactly = 1) { fixture.adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `already started attempt resumes observation without a second start`() {
        val fixture = fixture(id = "resume", preStarted = true)
        val result = fixture.service.resolveAndRecord(scope, namespace, fixture.workflow, step, root)
        assertThat(result.outcome).isInstanceOf(io.whozoss.factory.capability.CapabilityOutcome.AgentCompleted::class.java)
        verify(exactly = 0) { fixture.adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
        val expectedAttemptId = CapabilityExecutionService.stableAttemptId(fixture.workflow, step.id)
        verify(exactly = 1) {
            fixture.adapter.observeTurnResult(
                match { token -> token.caseId == CapabilityExecutionService.stableCaseId(fixture.workflow, step.id) && token.attemptId == expectedAttemptId },
                any(),
                any(),
                any(),
            )
        }
    }

    private inner class Fixture(val service: CapabilityExecutionService,val adapter: AgentOsExecutionAdapter, val workflow: String, val preflightReady: java.util.concurrent.atomic.AtomicBoolean? = null) {
        fun assertRefused(type: Class<out Throwable>, message: String) {
            assertThatThrownBy { service.resolveAndRecord(scope,namespace,workflow,step,root) }
                .isInstanceOf(type)
                .hasMessageContaining(message)
            verify(exactly=0) { adapter.startTurn(any<TrustedCaseBinding>(),any(),any()) }
            verify(exactly=0) { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(),any(),any()) }
        }
    }

    private fun fixture(id: String = java.util.UUID.randomUUID().toString(), workspacePath: Path=root,response: Map<String,Any?>?=null,remoteFailure:Boolean=false,staleRevision:Boolean=false,preStarted:Boolean=false,mutablePreflight:Boolean=false): Fixture {
        val scenarioRunId = "$runId-$id"
        val scenarioWorkflowId = "$workflowId-$id"
        val environmentId = "env-check-$id"
        Files.createDirectories(root)
        val rootCase="root-workspace-check"
        runs.insert(
            scope,
            RunRecord(
                namespaceId = namespace,
                runId = scenarioRunId,
                runType = "test",
                selectorVersion = "1",
                title = scenarioRunId,
                rootCaseId = rootCase,
            ),
        )
        workflows.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = scenarioWorkflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "hash",
                instance = mapOf(
                    "runId" to scenarioRunId,
                    "workflowType" to "test",
                ),
                projection = mapOf(
                    "steps" to listOf(
                        mapOf(
                            "id" to "writer",
                            "name" to "Writer",
                            "responsibility" to mapOf(
                                "kind" to "agent",
                                "name" to "Writer",
                            ),
                            "dependsOn" to emptyList<String>(),
                        ),
                    ),
                ),
                rootCaseId = rootCase,
            ),
        )
        environments.insert(
            scope,
            WorkEnvironment(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                environmentId = environmentId,
                workUnitId = "wu",
                workflowId = scenarioRunId,
                namespaceId = namespace,
                parentCaseId = rootCase,
                repoRoot = root.toString(),
                integrationBranch = "main",
                branch = "branch",
                worktreePath = root.toString(),
                lifecycleState = WorkEnvironmentState.READY,
                createdBy = "test",
                revision = 1,
            ),
        )
        val proxy=mockk<AgentOsProxyClient>()
        val preflightReady = if (mutablePreflight) java.util.concurrent.atomic.AtomicBoolean(false) else null
        if(remoteFailure) every { proxy.fetchWorkspaceChanges(any(),any()) } throws IllegalStateException("down")
        else every { proxy.fetchWorkspaceChanges(any(),any()) } answers {
            if (preflightReady != null && !preflightReady.get()) {
                mapOf("equipped" to true, "status" to "PROVISIONING", "path" to workspacePath.toString())
            } else {
                response ?: mapOf("equipped" to true,"status" to "READY","path" to workspacePath.toString())
            }
        }
        val adapter=mockk<AgentOsExecutionAdapter>(relaxed=true)
        every { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(),any(),any()) } answers {
            firstArg<TrustedCaseBinding>().let { CaseHandle(it.caseId,it.namespaceId,false) }
        }
        every { adapter.observeTurnResult(any(), any(), any(), any()) } returns AgentOsObservationResult(
            AgentOsExecutionVerdict.Succeeded(emptyMap()),
            null,
        )
        val service=CapabilityExecutionService(CapabilityResolver(),workflows,evidence,interactions,attempts,durableAgentAttemptService=durableAttempts,agentOsExecutionAdapter=adapter,transactionManager=transactionManager,agentLeaseTtlMs=0,workEnvironmentRepository=environments,agentOsWorkspaceIdentityValidator=AgentOsWorkspaceIdentityValidator(proxy),worktreeExecutionOccupancyService=WorktreeExecutionOccupancyService(environments, occupancyRepository, transactionManager = transactionManager))
        if (staleRevision) {
            // Reserve the attempt against revision 1, then advance the environment before execution.
            val attemptId = CapabilityExecutionService.stableAttemptId(scenarioWorkflowId, step.id)
            val caseId = CapabilityExecutionService.stableCaseId(scenarioWorkflowId, step.id)
            durableAttempts.register(
                scope,
                io.whozoss.factory.agentattempt.domain.DurableAgentAttempt(
                    attemptId = attemptId,
                    caseId = caseId,
                    namespaceId = namespace,
                    workflowId = scenarioWorkflowId,
                    stepId = step.id,
                    attemptNumber = 1,
                    agentName = "Writer",
                    environmentRef = environmentId,
                    expectedEnvironmentRevision = 1,
                    rootCaseId = rootCase,
                    parentCaseId = rootCase,
                ),
            )
            val environment = environments.findByEnvironmentId(scope, environmentId)!!
            environments.updateState(scope, environment.copy(branch = "changed"), environment.revision, java.time.Instant.now())
        }
        if (preStarted) {
            val attemptId = CapabilityExecutionService.stableAttemptId(scenarioWorkflowId, step.id)
            val caseId = CapabilityExecutionService.stableCaseId(scenarioWorkflowId, step.id)
            durableAttempts.register(
                scope,
                io.whozoss.factory.agentattempt.domain.DurableAgentAttempt(
                    attemptId = attemptId,
                    caseId = caseId,
                    namespaceId = namespace,
                    workflowId = scenarioWorkflowId,
                    stepId = step.id,
                    attemptNumber = 1,
                    agentName = "Writer",
                    environmentRef = environmentId,
                    expectedEnvironmentRevision = 1,
                    rootCaseId = rootCase,
                    parentCaseId = rootCase,
                ),
            )
            durableAttempts.claim(scope, namespace, scenarioWorkflowId, step.id, attemptId, "crashed-owner", leaseTtlMs = 0)
            durableAttempts.transition(scope, namespace, scenarioWorkflowId, step.id, attemptId, "crashed-owner", io.whozoss.factory.agentattempt.domain.AgentAttemptStatus.STARTING)
        }
        return Fixture(service, adapter, scenarioWorkflowId, preflightReady)
    }
}
