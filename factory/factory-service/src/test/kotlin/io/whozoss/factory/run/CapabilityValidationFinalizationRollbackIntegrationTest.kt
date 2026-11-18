package io.whozoss.factory.run

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultClaim
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator
import io.whozoss.factory.workspace.WorkspaceDeltaCollector
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.sdk.spi.FactoryStepResultValidator
import io.whozoss.factory.sdk.spi.FactoryStepEvidenceObserver
import io.whozoss.factory.sdk.spi.StepEvidenceObservation
import io.whozoss.factory.sdk.spi.StepEvidenceObservationContext
import io.whozoss.factory.sdk.spi.FindingResolution
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.whozoss.factory.capability.CapabilityOutcome

class CapabilityValidationFinalizationRollbackIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var runRepository: RunRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired lateinit var interactionRepository: HumanInteractionRepository
    @Autowired lateinit var attemptRepository: AgentStepAttemptRepository
    @Autowired lateinit var realAttemptRepository: DurableAgentAttemptRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var sessionDefinitionCatalog: SessionDefinitionCatalog
    @Autowired lateinit var workEnvironmentRepository: WorkEnvironmentRepository
    @Autowired lateinit var occupancyRepository: io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
    @TempDir lateinit var worktreeRoot: Path

    private val namespace = "9f8a2c10-9e3b-4c5a-8a11-finalrollback"
    private val runId = "run-finalization-rollback"
    private val workflowId = "fragment-finalization-rollback"
    private val step = WorkflowStepDefinition("write", "Write", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Writer"), emptyList())

    @Test
    fun `direct workflow observes its root Case worktree without a parent Run environment`() {
        Files.createDirectories(worktreeRoot)
        val directWorkflowId = "direct-git-observation"
        val rootCaseId = CapabilityExecutionService.stableCaseId(directWorkflowId, step.id)
        workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = directWorkflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "direct-observation-hash",
                instance = mapOf("workflowId" to directWorkflowId, "workflowType" to "test.direct"),
                projection = mapOf("steps" to listOf(mapOf(
                    "id" to step.id,
                    "name" to step.name,
                    "responsibility" to mapOf("kind" to "agent", "name" to "Writer"),
                    "dependsOn" to emptyList<String>(),
                ))),
                rootCaseId = rootCaseId,
            ),
        )
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = firstArg<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        val runtimeProof = io.whozoss.factory.adapter.agentos.VerdictDeriver.RuntimeCaseTerminationProof(
            caseId = rootCaseId, attemptId = CapabilityExecutionService.stableAttemptId(directWorkflowId, step.id),
            eventId = "direct-idle", status = "IDLE",
        )
        every { adapter.observeTurnResult(any(), any(), any(), any()) } returns
            io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
                AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "packet")), runtimeProof,
            )
        val workspaceProxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { workspaceProxy.fetchWorkspaceChanges(rootCaseId, any()) } returns mapOf(
            "equipped" to true, "status" to "READY", "path" to worktreeRoot.toString(),
            "changes" to mapOf("files" to emptyList<Any>()),
        )
        val observed = AtomicInteger()
        val observer = object : FactoryStepEvidenceObserver {
            override fun supports(flowType: String, stepId: String, agentName: String) = flowType == "test.direct"
            override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
                assertThat(context.run.runId).isEqualTo(directWorkflowId)
                assertThat(context.worktreePath).isEqualTo(worktreeRoot.toString())
                observed.incrementAndGet()
                return StepEvidenceObservation.Observed(mapOf("verifiedCommit" to "a".repeat(40)))
            }
        }
        val capability = CapabilityExecutionService(
            resolver = CapabilityResolver(), workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository, interactionRepository = interactionRepository,
            attemptRepository = attemptRepository, durableAgentAttemptService = DurableAgentAttemptService(realAttemptRepository),
            agentOsExecutionAdapter = adapter, transactionManager = transactionManager,
            registeredStepEvidenceObservers = listOf(observer),
            workspaceDeltaCollector = WorkspaceDeltaCollector(workspaceProxy),
        )

        capability.resolveAndRecord(scope, namespace, directWorkflowId, step, Path.of("."))

        assertThat(observed.get()).isEqualTo(1)
        val accepted = evidenceRepository.list(scope, namespace, directWorkflowId)
            .single { it.kind == "agent-result" && it.outcome == "pass" }
        assertThat((accepted.facts["externalEvidence"] as Map<*, *>)["verifiedCommit"])
            .isEqualTo("a".repeat(40))
        assertThat(workEnvironmentRepository.findLatestByWorkflowId(scope, directWorkflowId)).isNull()
    }

    @Test
    fun `Git outage defers advancement then recovery reobserves after rollback and commits once`() {
        Files.createDirectories(worktreeRoot)
        val rootCaseId = "root-finalization-rollback"
        val initialRun = runRepository.insert(scope, RunRecord(namespaceId = namespace, runId = runId, runType = "test", selectorVersion = "1", title = runId, domainState = mapOf("before" to true), domainRevision = 3, rootCaseId = rootCaseId))
        workEnvironmentRepository.insert(
            scope,
            WorkEnvironment(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                environmentId = "$runId-env",
                workUnitId = "$runId-work-unit",
                workflowId = runId,
                namespaceId = namespace,
                parentCaseId = rootCaseId,
                repoRoot = worktreeRoot.toString(),
                integrationBranch = "main",
                branch = "factory/$runId",
                worktreePath = worktreeRoot.toString(),
                lifecycleState = WorkEnvironmentState.READY,
                createdBy = "test",
                revision = 1,
            ),
        )
        workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = workflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "hash-finalization-rollback",
                instance = mapOf(
                    "workflowId" to workflowId,
                    "workflowType" to "test.validation",
                    "runId" to runId,
                ),
                projection = mapOf(
                    "steps" to listOf(
                        mapOf(
                            "id" to "write",
                            "name" to "Write",
                            "responsibility" to mapOf("kind" to "agent", "name" to "Writer"),
                            "dependsOn" to emptyList<String>(),
                        ),
                    ),
                ),
                rootCaseId = rootCaseId,
            ),
        )

        val runService = RunService(runRepository, workflowRepository, evidenceRepository, interactionRepository, sessionDefinitionCatalog, transactionManager = transactionManager)
        val observations = AtomicInteger()
        val unavailableOnce = AtomicBoolean(true)
        val observer = object : FactoryStepEvidenceObserver {
            override fun supports(flowType: String, stepId: String, agentName: String) = flowType == "test.validation"
            override fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                assertThat(context.worktreePath).isEqualTo(worktreeRoot.toString())
                observations.incrementAndGet()
                if (unavailableOnce.getAndSet(false)) return StepEvidenceObservation.Unavailable(
                    "GIT_SOURCE_UNAVAILABLE", "The pinned Git source is temporarily unavailable.",
                )
                return StepEvidenceObservation.Observed(mapOf("verifiedCommit" to "a".repeat(40)))
            }
        }
        val validator = object : FactoryStepResultValidator {
            override fun supports(flowType: String, stepId: String, agentName: String) = flowType == "test.validation"
            override fun validate(context: StepValidationContext): StepValidationVerdict {
                assertThat(context.externalEvidence["verifiedCommit"]).isEqualTo("a".repeat(40))
                return StepValidationVerdict.Accepted(
                    newDomainState = mapOf("after" to true),
                    expectedDomainRevision = context.run.domainRevision,
                    findingResolutions = context.run.findings
                        .filter { it.id.startsWith("technical-pause:") && it.status == FindingStatus.OPEN }
                        .associate { it.id to FindingResolution(FindingStatus.RESOLVED, "Git source recovered") },
                )
            }
        }
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = firstArg<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        val runtimeProof = io.whozoss.factory.adapter.agentos.VerdictDeriver.RuntimeCaseTerminationProof(
            caseId = io.whozoss.factory.capability.CapabilityExecutionService.stableCaseId(workflowId, step.id),
            attemptId = io.whozoss.factory.capability.CapabilityExecutionService.stableAttemptId(workflowId, step.id),
            eventId = "runtime-idle-1",
            status = "IDLE",
        )
        every { adapter.observeTurnResult(any(), any(), any(), any()) } returns io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
            AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok")),
            runtimeProof,
        )

        val failOnce = AtomicBoolean(true)
        val failingRepository = object : DurableAgentAttemptRepository by realAttemptRepository {
            override fun finalize(scope: io.whozoss.factory.persistence.TenantScope, namespaceId: String, workflowId: String, stepId: String, attemptId: String, ownerToken: String, target: AgentAttemptStatus, failureCode: String?, resultEvidenceId: String?, lastObservedEventId: String?, now: Instant): DurableAgentAttempt {
                val result = realAttemptRepository.finalize(scope, namespaceId, workflowId, stepId, attemptId, ownerToken, target, failureCode, resultEvidenceId, lastObservedEventId, now)
                if (failOnce.getAndSet(false)) throw InjectedFinalizationFailure()
                return result
            }
        }
        val attempts = DurableAgentAttemptService(failingRepository)
        val workspaceProxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { workspaceProxy.fetchWorkspaceChanges(any(), any()) } returns mapOf(
            "equipped" to true,
            "status" to "READY",
            "path" to worktreeRoot.toString(),
            "changes" to mapOf("files" to emptyList<Any>()),
        )
        val capability = CapabilityExecutionService(
            resolver = CapabilityResolver(), workflowRepository = workflowRepository, evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository, attemptRepository = attemptRepository,
            durableAgentAttemptService = attempts, agentOsExecutionAdapter = adapter,
            transactionManager = transactionManager, agentLeaseTtlMs = 0L, runService = runService,
            registeredStepResultValidators = listOf(validator),
            registeredStepEvidenceObservers = listOf(observer),
            workEnvironmentRepository = workEnvironmentRepository,
            agentOsWorkspaceIdentityValidator = AgentOsWorkspaceIdentityValidator(workspaceProxy),
            worktreeExecutionOccupancyService = io.whozoss.factory.worktree.WorktreeExecutionOccupancyService(workEnvironmentRepository, occupancyRepository, transactionManager = transactionManager),
        )
        val attemptId = CapabilityExecutionService.stableAttemptId(workflowId, step.id)

        val paused = capability.resolveAndRecord(scope, namespace, workflowId, step, Path.of("."))
        assertThat(paused.outcome).isInstanceOf(CapabilityOutcome.AgentDeferred::class.java)
        val pausedRun = runRepository.find(scope, namespace, runId)!!
        assertThat(pausedRun.domainState).isEqualTo(initialRun.domainState)
        assertThat(pausedRun.findings.single().code).isEqualTo("GIT_SOURCE_UNAVAILABLE")
        assertThat(pausedRun.findings.single().status).isEqualTo(FindingStatus.OPEN)
        assertThat(attempts.find(scope, namespace, workflowId, step.id, attemptId)!!.status).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(evidenceRepository.list(scope, namespace, workflowId).single().outcome).isEqualTo("technical_pause")

        assertThatThrownBy { capability.resolveAndRecord(scope, namespace, workflowId, step, Path.of(".")) }
            .isInstanceOf(InjectedFinalizationFailure::class.java)

        val rolledBackRun = runRepository.find(scope, namespace, runId)!!
        assertThat(rolledBackRun.revision).isEqualTo(pausedRun.revision)
        assertThat(rolledBackRun.domainRevision).isEqualTo(pausedRun.domainRevision)
        assertThat(rolledBackRun.domainState).isEqualTo(pausedRun.domainState)
        assertThat(rolledBackRun.appliedValidationUpdates).isEqualTo(pausedRun.appliedValidationUpdates)
        assertThat(evidenceRepository.list(scope, namespace, workflowId)).hasSize(1)
        val rolledBackAttempt = attempts.find(scope, namespace, workflowId, step.id, attemptId)!!
        assertThat(rolledBackAttempt.status).isEqualTo(AgentAttemptStatus.RUNNING)
        assertThat(attempts.journal(scope, namespace, workflowId, step.id, attemptId).map { it.toStatus })
            .doesNotContain(AgentAttemptStatus.SUCCEEDED)

        val recoveryOwner = "recovery-test-owner"
        attempts.claim(scope, namespace, workflowId, step.id, attemptId, recoveryOwner, leaseTtlMs = 0L)
        attempts.transition(scope, namespace, workflowId, step.id, attemptId, recoveryOwner, AgentAttemptStatus.STARTING)
        attempts.transition(scope, namespace, workflowId, step.id, attemptId, recoveryOwner, AgentAttemptStatus.RUNNING)
        assertThat(capability.finalizeRecoveredAttempt(scope, rolledBackAttempt, recoveryOwner,
            io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
                AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "ok")), runtimeProof,
            ))).isTrue()
        assertThat(observations.get()).isEqualTo(3)
        val committedRun = runRepository.find(scope, namespace, runId)!!
        assertThat(committedRun.domainState).containsExactlyEntriesOf(mapOf("after" to true))
        assertThat(committedRun.domainRevision).isEqualTo(initialRun.domainRevision + 1)
        assertThat(committedRun.appliedValidationUpdates).hasSize(2)
        assertThat(committedRun.findings.single().status).isEqualTo(FindingStatus.RESOLVED)
        val agentEvidence = evidenceRepository.list(scope, namespace, workflowId).filter { it.kind == "agent-result" && it.outcome == "pass" }
        assertThat(agentEvidence).hasSize(1)
        assertThat(agentEvidence.single().facts["externalEvidence"]).isEqualTo(mapOf("verifiedCommit" to "a".repeat(40)))
        assertThat(agentEvidence.single().facts["runtimeCaseTerminationProof"]).isEqualTo(
            mapOf(
                "caseId" to runtimeProof.caseId,
                "attemptId" to runtimeProof.attemptId,
                "eventId" to runtimeProof.eventId,
                "status" to runtimeProof.status,
            ),
        )
        assertThat(attempts.find(scope, namespace, workflowId, step.id, attemptId)!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)

        val replayed = capability.resolveAndRecord(scope, namespace, workflowId, step, Path.of("."))
        assertThat(replayed.attemptId).isEqualTo(attemptId)
        val afterReplay = runRepository.find(scope, namespace, runId)!!
        assertThat(afterReplay.revision).isEqualTo(committedRun.revision)
        assertThat(afterReplay.domainRevision).isEqualTo(committedRun.domainRevision)
        assertThat(evidenceRepository.list(scope, namespace, workflowId).filter { it.kind == "agent-result" && it.outcome == "pass" }).hasSize(1)
    }

    @Test
    fun `public capability accepts PASS after timeout without persisting runtime termination proof`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.PASS)
    }

    @Test
    fun `public capability accepts FAIL after timeout and releases worktree occupancy`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.FAIL)
    }

    @Test
    fun `public capability NEEDS_RESEARCH releases worktree occupancy`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.NEEDS_RESEARCH)
    }

    @Test
    fun `public capability timeout without accepted result retains worktree occupancy`() {
        assertAcceptedAfterTimeout(null)
    }

    @Test
    fun `committed finalization with skipped release is recovered by public replay without second turn`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.PASS, recovery = "replay")
    }

    @Test
    fun `committed finalization with skipped release is recovered by startup sweep without second turn`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.PASS, recovery = "sweep")
    }

    @Test
    fun `session routes NEEDS_RESEARCH through Searcher and completes rearmed attempt on generations one two three`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.NEEDS_RESEARCH, researchSession = true)
    }

    @Test
    fun `normal child divergence after same identity creation prevents turn`() {
        assertAcceptedAfterTimeout(AgentStepResultStatus.PASS, childDivergent = true)
    }

    private fun assertAcceptedAfterTimeout(resultStatus: AgentStepResultStatus?, recovery: String? = null, researchSession: Boolean = false, childDivergent: Boolean = false) {
        Files.createDirectories(worktreeRoot)
        val testRunId = "$runId-accepted-pass"
        val testWorkflowId = "$workflowId-accepted-pass"
        val rootCaseId = "root-finalization-accepted-pass"
        runRepository.insert(scope, RunRecord(namespaceId = namespace, runId = testRunId, runType = "test", selectorVersion = "1", title = testRunId, rootCaseId = rootCaseId))
        workEnvironmentRepository.insert(
            scope,
            WorkEnvironment(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                environmentId = "$testRunId-env",
                workUnitId = "$testRunId-work-unit",
                workflowId = testRunId,
                namespaceId = namespace,
                parentCaseId = rootCaseId,
                repoRoot = worktreeRoot.toString(),
                integrationBranch = "main",
                branch = "factory/$testRunId",
                worktreePath = worktreeRoot.toString(),
                lifecycleState = WorkEnvironmentState.READY,
                createdBy = "test",
                revision = 1,
            ),
        )
        workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = testWorkflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "hash-finalization-accepted-pass",
                instance = mapOf("workflowId" to testWorkflowId, "workflowType" to "test.accepted-pass", "runId" to testRunId),
                projection = mapOf(
                    "steps" to listOf(
                        mapOf(
                            "id" to step.id,
                            "name" to step.name,
                            "responsibility" to mapOf("kind" to "agent", "name" to "Writer"),
                            "dependsOn" to emptyList<String>(),
                        ),
                    ),
                ),
                rootCaseId = rootCaseId,
            ),
        )

        val attemptId = CapabilityExecutionService.stableAttemptId(testWorkflowId, step.id)
        val caseId = CapabilityExecutionService.stableCaseId(testWorkflowId, step.id)
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = firstArg<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        every { adapter.observeTurnResult(any(), any(), any(), any()) } returns io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
            AgentOsExecutionVerdict.Indeterminate("OBSERVATION_TIMEOUT", mapOf("source" to "test-timeout")),
            null,
        )
        val acceptedResultService = mockk<AgentStepResultService>()
        every { acceptedResultService.issue(any(), any(), any(), any()) } returns io.whozoss.factory.agentattempt.domain.IssuedCapability(
            token = "accepted-pass-token",
            expiresAt = "2030-01-01T00:00:00Z",
        )
        every { acceptedResultService.acceptedResult(scope, namespace, testWorkflowId, step.id, attemptId) } returns AgentStepResultSubmitted(
            type = "result-submitted",
            resultId = "accepted-pass-result",
            attemptId = attemptId,
            workflowId = testWorkflowId,
            stepId = step.id,
            namespaceId = namespace,
            caseId = caseId,
            agentName = "Writer",
            briefHash = "accepted-pass-brief",
            status = resultStatus ?: AgentStepResultStatus.PASS,
            summary = "business result accepted after observation timeout",
            artifacts = emptyList(),
            claims = AgentStepResultClaim(emptyList()),
            findings = emptyList(),
            submittedAt = "2026-01-01T00:00:00Z",
            resultHash = "accepted-pass-hash",
        )
        if (resultStatus == null) {
            every { acceptedResultService.acceptedResult(scope, namespace, testWorkflowId, step.id, attemptId) } returns null
            every { adapter.reconcileResult(any()) } returns io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
                AgentOsExecutionVerdict.Indeterminate("OBSERVATION_TIMEOUT"),
            )
        }
        val workspaceProxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { workspaceProxy.fetchWorkspaceChanges(any(), any()) } returns mapOf(
            "equipped" to true,
            "status" to "READY",
            "path" to worktreeRoot.toString(),
            "changes" to mapOf("files" to emptyList<Any>()),
        )
        val attempts = DurableAgentAttemptService(realAttemptRepository)
        val capability = CapabilityExecutionService(
            resolver = CapabilityResolver(),
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            attemptRepository = attemptRepository,
            durableAgentAttemptService = attempts,
            agentStepResultService = acceptedResultService,
            agentOsExecutionAdapter = adapter,
            transactionManager = transactionManager,
            workEnvironmentRepository = workEnvironmentRepository,
            agentOsWorkspaceIdentityValidator = AgentOsWorkspaceIdentityValidator(workspaceProxy),
            worktreeExecutionOccupancyService = io.whozoss.factory.worktree.WorktreeExecutionOccupancyService(workEnvironmentRepository, occupancyRepository, transactionManager = transactionManager),
        )

        if (researchSession) {
            assertResearchSession(testWorkflowId, testRunId, capability, adapter, acceptedResultService, attempts, attemptId)
            return
        }
        if (recovery != null) {
            val accepted = AgentStepResultSubmitted(
                type = "result-submitted", resultId = "crash-result", attemptId = attemptId, workflowId = testWorkflowId,
                stepId = step.id, namespaceId = namespace, caseId = caseId, agentName = "Writer", briefHash = "brief",
                status = AgentStepResultStatus.PASS, summary = "done", artifacts = emptyList(), claims = AgentStepResultClaim(emptyList()),
                findings = emptyList(), submittedAt = "2026-01-01T00:00:00Z", resultHash = "crash-hash",
            )
            var reads = 0
            every { acceptedResultService.acceptedResult(scope, namespace, testWorkflowId, step.id, attemptId) } answers {
                reads++
                if (reads == 2) null else accepted
            }
            capability.resolveAndRecord(scope, namespace, testWorkflowId, step, worktreeRoot)
            val committed = attempts.find(scope, namespace, testWorkflowId, step.id, attemptId)!!
            assertThat(committed.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
            val identity = committed.occupancyResourceIdentity!!
            assertThat(occupancyRepository.find(identity)!!.status).isEqualTo(io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.ACTIVE)
            if (recovery == "replay") {
                val replayed = capability.resolveAndRecord(scope, namespace, testWorkflowId, step, worktreeRoot)
                assertThat(replayed.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
            } else {
                val report = io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker(
                    attempts, adapter, resultService = acceptedResultService,
                    worktreeOccupancyService = io.whozoss.factory.worktree.WorktreeExecutionOccupancyService(workEnvironmentRepository, occupancyRepository, transactionManager = transactionManager),
                ).recover()
                assertThat(report.scanned).isZero()
                verify(exactly = 0) { adapter.reconcileResult(any()) }
            }
            assertThat(occupancyRepository.find(identity)!!.status).isEqualTo(io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.RELEASED)
            assertThat(attempts.find(scope, namespace, testWorkflowId, step.id, attemptId)!!.revision).isEqualTo(committed.revision)
            assertThat(evidenceRepository.list(scope, namespace, testWorkflowId).filter { it.kind == "agent-result" }).hasSize(1)
            verify(exactly = 1) { adapter.startTurn(any<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>(), any(), any()) }
            verify(exactly = 1) { adapter.observeTurnResult(any(), any(), any(), any()) }
            return
        }
        val preflightOrder = mutableListOf<String>()
        every { workspaceProxy.fetchWorkspaceChanges(any(), any()) } answers {
            preflightOrder.add(if (firstArg<String>() == rootCaseId) "root" else "child")
            mapOf("equipped" to true, "status" to "READY", "path" to
                if (childDivergent && firstArg<String>() != rootCaseId) worktreeRoot.resolve("missing-child").toString() else worktreeRoot.toString())
        }
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            preflightOrder.add("create")
            val binding = firstArg<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        every { adapter.startTurn(any<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>(), any(), any()) } answers {
            preflightOrder.add("turn")
            val binding = firstArg<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>()
            io.whozoss.factory.adapter.agentos.TurnToken.unbaselined(binding.caseId, binding.attemptId)
        }
        if (childDivergent) {
            assertThatThrownBy { capability.resolveAndRecord(scope, namespace, testWorkflowId, step, worktreeRoot) }
                .isInstanceOf(io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidationException::class.java)
            assertThat(preflightOrder).containsExactly("root", "create", "child")
            verify(exactly = 0) { adapter.startTurn(any<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>(), any(), any()) }
            return
        }
        val execution = capability.resolveAndRecord(scope, namespace, testWorkflowId, step, worktreeRoot)
        assertThat(preflightOrder).containsExactly("root", "create", "child", "turn")

        val expectedStatus = when (resultStatus) {
            null -> {
                assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentFailed::class.java)
                AgentAttemptStatus.INDETERMINATE
            }
            AgentStepResultStatus.PASS -> {
                assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
                AgentAttemptStatus.SUCCEEDED
            }
            AgentStepResultStatus.FAIL -> {
                assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentFailed::class.java)
                assertThat((execution.outcome as CapabilityOutcome.AgentFailed).code).isEqualTo("FACTORY_STEP_RESULT_FAILED")
                AgentAttemptStatus.FAILED
            }
            AgentStepResultStatus.NEEDS_RESEARCH -> {
                assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentNeedsResearch::class.java)
                AgentAttemptStatus.INDETERMINATE
            }
        }
        val evidence = evidenceRepository.list(scope, namespace, testWorkflowId).single { it.kind == "agent-result" }
        assertThat(evidence.outcome).isEqualTo(when (resultStatus) {
            null -> "fail"
            AgentStepResultStatus.PASS -> "pass"
            AgentStepResultStatus.FAIL -> "fail"
            AgentStepResultStatus.NEEDS_RESEARCH -> "needs_research"
        })
        assertThat(evidence.facts).doesNotContainKey("runtimeCaseTerminationProof")
        val terminal = attempts.find(scope, namespace, testWorkflowId, step.id, attemptId)!!
        assertThat(terminal.status).isEqualTo(expectedStatus)
        assertThat(occupancyRepository.find(terminal.occupancyResourceIdentity!!)!!.status)
            .isEqualTo(if (resultStatus == null) io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.ACTIVE else io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.RELEASED)
        verify(exactly = if (resultStatus == null) 2 else 1) { adapter.observeTurnResult(any(), any(), any(), any()) }
        verify(exactly = 2) { acceptedResultService.acceptedResult(scope, namespace, testWorkflowId, step.id, attemptId) }
    }

    private fun assertResearchSession(
        workflow: String,
        parentRun: String,
        capability: CapabilityExecutionService,
        adapter: AgentOsExecutionAdapter,
        results: AgentStepResultService,
        attempts: DurableAgentAttemptService,
        firstAttemptId: String,
    ) {
        val steps = listOf(mapOf(
            "id" to step.id, "name" to step.name, "responsibility" to mapOf("kind" to "agent", "name" to "Writer"),
            "dependsOn" to emptyList<String>(),
        ))
        workflowRepository.saveDefinition(scope, io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord(
            workflowType = "test.research-occupancy", version = "1", definitionHash = "research-hash",
            definition = mapOf("steps" to steps),
        ))
        val original = workflowRepository.findInstance(scope, namespace, workflow)!!
        assertThat(workflowRepository.updateInstance(scope, namespace, workflow, original.revision, original.copy(
            revision = original.revision + 1,
            instance = original.instance + mapOf("workflowType" to "test.research-occupancy", "definitionVersion" to "1", "definitionHash" to "research-hash"),
        ))).isTrue()
        val identity = io.whozoss.factory.worktree.LocalWorktreeResourceIdentityResolver().resolve(worktreeRoot)
        val observed = mutableListOf<DurableAgentAttempt>()
        every { adapter.observeTurnResult(any(), any(), any(), any()) } answers {
            val turn = firstArg<io.whozoss.factory.adapter.agentos.TurnToken>()
            val current = attempts.findByWorkflow(scope, namespace, workflow).single { it.attemptId == turn.attemptId }
            val active = occupancyRepository.find(identity)!!
            assertThat(active.status).isEqualTo(io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.ACTIVE)
            assertThat(active.generation).isEqualTo((observed.size + 1).toLong())
            assertThat(current.occupancyGeneration).isEqualTo(active.generation)
            assertThat(current.occupancyResourceIdentity).isEqualTo(identity)
            if (observed.isNotEmpty()) {
                val prior = attempts.find(scope, namespace, workflow, observed.last().stepId, observed.last().attemptId)!!
                assertThat(prior.status.terminal).isTrue()
            }
            observed.add(current)
            io.whozoss.factory.adapter.agentos.AgentOsObservationResult(AgentOsExecutionVerdict.Indeterminate("OBSERVATION_TIMEOUT"))
        }
        every { results.acceptedResult(scope, namespace, workflow, any(), any()) } answers {
            val stepId = arg<String>(3)
            val id = arg<String>(4)
            val current = attempts.find(scope, namespace, workflow, stepId, id)!!
            val status = if (id == firstAttemptId) AgentStepResultStatus.NEEDS_RESEARCH else AgentStepResultStatus.PASS
            // This read runs both before finalization and immediately after commit, before release.
            if (current.status.terminal) {
                assertThat(occupancyRepository.find(identity)!!.generation).isEqualTo(current.occupancyGeneration)
            }
            AgentStepResultSubmitted(
                type = "result-submitted", resultId = "result-$id", attemptId = id, workflowId = workflow, stepId = stepId,
                namespaceId = namespace, caseId = current.caseId, agentName = current.agentName, briefHash = "brief",
                status = status, summary = "research result", artifacts = emptyList(), claims = AgentStepResultClaim(emptyList()),
                findings = emptyList(), submittedAt = "2026-01-01T00:00:00Z", resultHash = "hash-$id",
            )
        }
        val router = io.whozoss.factory.capability.NeedsResearchRouter(attempts, evidenceRepository, results, attemptRepository)
        val session = io.whozoss.factory.workflow.service.SessionRunService(
            workflowRepository, evidenceRepository, interactionRepository, capability,
            mockk<io.whozoss.factory.workflow.sse.WorkflowSseHub>(relaxed = true),
            transactionManager = transactionManager, needsResearchRouter = router,
        )
        val completed = session.runSession(scope, namespace, workflow, worktreeRoot)
        assertThat(completed.status).isEqualTo("completed")
        assertThat(completed.steps.single().status).isEqualTo("completed")
        assertThat(observed).hasSize(3)
        assertThat(observed.map { it.agentName }).containsExactly("Writer", "Searcher", "Writer")
        assertThat(observed.map { it.occupancyGeneration }).containsExactly(1L, 2L, 3L)
        assertThat(observed.map { it.environmentRef }.distinct()).containsExactly("$parentRun-env")
        assertThat(observed.last().attemptNumber).isEqualTo(2)
        assertThat(observed.last().attemptId).isEqualTo(CapabilityExecutionService.retryAttemptId(workflow, step.id, 2))
        assertThat(attempts.find(scope, namespace, workflow, step.id, firstAttemptId)!!.failureCode).isEqualTo("AGENT_NEEDS_RESEARCH")
        observed.drop(1).forEach { row ->
            assertThat(attempts.find(scope, namespace, workflow, row.stepId, row.attemptId)!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        }
        assertThat(occupancyRepository.find(identity)!!.generation).isEqualTo(3L)
        assertThat(occupancyRepository.find(identity)!!.status).isEqualTo(io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.RELEASED)
        verify(exactly = 3) { adapter.startTurn(any<io.whozoss.factory.adapter.agentos.TrustedCaseBinding>(), any(), any()) }
    }

    private class InjectedFinalizationFailure : RuntimeException("injected after durable attempt finalization")
}
