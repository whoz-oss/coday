package io.whozoss.factory.run

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsAdapterProperties
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.adapter.agentos.TrustedCaseBinding
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import io.whozoss.factory.run.domain.RunStatuses
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.run.service.RunStartCommand
import io.whozoss.factory.sdk.spi.BriefContext
import io.whozoss.factory.sdk.spi.BriefContribution
import io.whozoss.factory.sdk.spi.FactoryBriefContributor
import io.whozoss.factory.sdk.spi.FactoryFlowCatalogContributor
import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FactoryStepResultValidator
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.sdk.spi.StepValidationContext
import io.whozoss.factory.sdk.spi.StepValidationVerdict
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workspace.WorkspaceDeltaCollector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for **Lot 2 & Lot 3**:
 * - [WorkspaceDeltaCollector] before/after snapshot around an agent turn
 * - [FactoryBriefContributor] injecting BMAD sections and `watchedPaths`
 * - [FactoryStepResultValidator] rejecting a step when:
 *   1. `SPEC.md` is a phantom artifact (`sha256After == sha256Before`)
 *   2. Workspace evidence is unavailable (`WORKSPACE_EVIDENCE_UNAVAILABLE`, not phantom artifact)
 *   3. `SPEC.md` is modified during an unauthorized step (`SPEC_WRITE_LOCK_VIOLATION`)
 *   and accepting the step + updating `domainState` when `SPEC.md` is properly amended.
 */
class WorkspaceDeltaAndValidatorIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var runRepository: RunRepository

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var attemptRepository: AgentStepAttemptRepository

    @Autowired
    private lateinit var durableAgentAttemptService: DurableAgentAttemptService

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var workEnvironmentRepository: WorkEnvironmentRepository

    @Autowired
    private lateinit var worktreeOccupancyRepository: Neo4jWorktreeExecutionOccupancyRepository

    @TempDir
    lateinit var worktreeRoot: Path

    @Autowired lateinit var provisioningIntents: io.whozoss.factory.run.persistence.RunProvisioningIntentRepository
    private val namespace = "7a1c3e20-1b2c-4d5e-9f00-lot2delta0001"

    private val catalogContributor = object : FactoryFlowCatalogContributor {
        override fun flows(): List<String> = listOf(
            """
            {
              "schemaVersion": "1",
              "workflowType": "test.spec-step",
              "version": "1.0.0",
              "title": "Test Spec Validation Flow",
              "steps": [
                {
                  "id": "write-spec",
                  "name": "Write Spec",
                  "responsibility": { "kind": "agent", "name": "ForgeProductWorker" },
                  "dependsOn": []
                }
              ]
            }
            """.trimIndent(),
        )
    }

    private val briefContributor = object : FactoryBriefContributor {
        override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
            flowType == "test.spec-step"

        override fun contribute(context: BriefContext): BriefContribution = BriefContribution(
            sections = listOf("## Custom BMAD Spec Rule\nMust update SPEC.md"),
            watchedPaths = listOf("SPEC.md"),
        )
    }

    private val specValidator = object : FactoryStepResultValidator {
        override fun supports(flowType: String, stepId: String, agentName: String): Boolean =
            flowType == "test.spec-step"

        override fun validate(context: StepValidationContext): StepValidationVerdict {
            val snap = context.workspaceDelta.watchedFiles["SPEC.md"]
            if (snap == null || !snap.existsAfter || snap.sha256After == snap.sha256Before) {
                return StepValidationVerdict.Rejected(
                    reasonCode = "PHANTOM_SPEC_ARTIFACT",
                    findings = listOf(
                        NewFinding(
                            code = "PHANTOM_SPEC_ARTIFACT",
                            severity = FindingSeverity.CRITICAL,
                            message = "SPEC.md was not modified during turn",
                            stepId = context.stepId,
                            filePath = "SPEC.md",
                        ),
                    ),
                )
            }
            return StepValidationVerdict.Accepted(
                newDomainState = context.run.domainState + mapOf(
                    "spec_sha256" to snap.sha256After,
                    "product_rev" to 1,
                ),
            )
        }
    }

    private val selector = object : FactoryFlowSelector {
        override fun runTypes(): Set<String> = setOf("test-lot2-run")

        override fun start(run: RunView): Pair<RunUpdate, FlowDecision> =
            RunUpdate(newDomainState = mapOf("product_rev" to 0)) to FlowDecision.Apply(
                flowType = "test.spec-step",
                reason = "Start spec step",
            )

        override fun reduce(run: RunView, outcome: FragmentOutcome): RunUpdate = RunUpdate()

        override fun next(run: RunView): FlowDecision {
            val last = run.fragments.lastOrNull()
            return if (last?.status == WorkflowStatuses.COMPLETED) {
                FlowDecision.Complete("Spec validated")
            } else {
                FlowDecision.EscalateHuman(
                    question = "Spec step failed validation",
                    options = listOf("retry", "abort"),
                    reason = "VALIDATION_REJECTED",
                )
            }
        }
    }

    @Test
    fun `phantom artifact is rejected, unavailable workspace yields WORKSPACE_EVIDENCE_UNAVAILABLE, and valid delta updates domainState and completes`() {
        val fakeAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { fakeAdapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val b = invocation.args[0] as TrustedCaseBinding
            CaseHandle(b.caseId, b.namespaceId, false)
        }
        every { fakeAdapter.observeTurnResult(any(), any(), any(), any()) } returns io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
            AgentOsExecutionVerdict.Succeeded(
                outputs = mapOf("summary" to "wrote SPEC.md"),
                evidence = mapOf("source" to "test"),
            ),
            null,
        )

        Files.createDirectories(worktreeRoot)
        val preflightProxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { preflightProxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { preflightProxy.fetchWorkspaceChanges(any(), any()) } returns mapOf(
            "equipped" to true,
            "status" to "READY",
            "path" to worktreeRoot.toString(),
            "changes" to mapOf("files" to emptyList<Any>()),
        )
        val workspaceValidator = AgentOsWorkspaceIdentityValidator(preflightProxy)
        val acceptedResults = mockk<io.whozoss.factory.agentattempt.service.AgentStepResultService>(relaxed = true)
        every { acceptedResults.acceptedResult(any(), any(), any(), any(), any()) } answers {
            val attemptId = invocation.args[4] as String
            io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted(
                type = "result-submitted", resultId = "result-$attemptId", attemptId = attemptId,
                workflowId = invocation.args[2] as String, stepId = invocation.args[3] as String,
                namespaceId = invocation.args[1] as String, caseId = "case-$attemptId", agentName = "test",
                briefHash = "hash", status = io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.PASS,
                summary = "ok", artifacts = emptyList(), claims = io.whozoss.factory.agentattempt.domain.AgentStepResultClaim(emptyList()),
                findings = emptyList(), submittedAt = "2026-01-01T00:00:00Z", resultHash = "hash-$attemptId",
            )
        }

        val pluginManager = mockk<PluginManager>()
        every { pluginManager.getExtensions(FactoryFlowCatalogContributor::class.java) } returns listOf(catalogContributor)
        every { pluginManager.getExtensions(FactoryFlowSelector::class.java) } returns listOf(selector)
        every { pluginManager.getExtensions(FactoryBriefContributor::class.java) } returns listOf(briefContributor)
        every { pluginManager.getExtensions(FactoryStepResultValidator::class.java) } returns listOf(specValidator)
        every { pluginManager.getExtensions(io.whozoss.factory.sdk.spi.FactoryRunLifecycleObserver::class.java) } returns emptyList()

        // --- Case 1: Phantom artifact (sha256Before == sha256After) -> Rejected("PHANTOM_SPEC_ARTIFACT") ---
        val proxyPhantom = mockk<AgentOsProxyClient>(relaxed = true)
        every { proxyPhantom.fetchWorkspaceChanges(any(), any()) } returns mapOf(
            "equipped" to true,
            "status" to "READY",
            "changes" to mapOf(
                "base" to "aaaa",
                "files" to listOf(
                    mapOf("path" to "SPEC.md", "status" to "MODIFIED", "exists" to true, "contentSha256" to "same-hash"),
                ),
            ),
        )
        every { proxyPhantom.fetchWorkspaceDiff(any(), eq("SPEC.md"), any()) } returns mapOf("patch" to "@@ unchanged")

        val collectorPhantom = WorkspaceDeltaCollector(proxyPhantom)
        val catalog = SessionDefinitionCatalog(objectMapper, pluginManager)
        val runService = RunService(
            runRepository = runRepository,
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            sessionDefinitionCatalog = catalog,
            workspaceProvisioning = io.whozoss.factory.run.service.ParentRunWorkspaceProvisioningService(
                runRepository, workEnvironmentRepository, provisioningIntents, preflightProxy, objectMapper, transactionManager,
            ),
            transactionManager = transactionManager,
            sseHub = sseHub,
            pluginManager = pluginManager,
        )
        val capServicePhantom = CapabilityExecutionService(
            resolver = CapabilityResolver(),
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            attemptRepository = attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentStepResultService = acceptedResults,
            agentOsExecutionAdapter = fakeAdapter,
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = true),
            workspaceDeltaCollector = collectorPhantom,
            pluginManager = pluginManager,
            runService = runService,
            transactionManager = transactionManager,
            workEnvironmentRepository = workEnvironmentRepository,
            agentOsWorkspaceIdentityValidator = workspaceValidator,
            worktreeExecutionOccupancyService = WorktreeExecutionOccupancyService(workEnvironmentRepository, worktreeOccupancyRepository, transactionManager = transactionManager),
        )
        val sessionPhantom = SessionRunService(
            repository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            capabilityExecutionService = capServicePhantom,
            sseHub = sseHub,
            runService = runService,
        )

        // Real provisioning service adopts the simulated AgentOS READY workspace.
        val run1 = runService.startRun(
            scope,
            namespace,
            RunStartCommand(runId = "run-phantom", runType = "test-lot2-run", title = "Phantom Test"), actorId = "test-user",
        )
        sessionPhantom.runSession(scope, namespace, "${run1.runId}__f1", java.nio.file.Path.of("."))
        val afterPhantom = runService.getRun(scope, namespace, "run-phantom")!!
        assertThat(afterPhantom.status).isEqualTo(RunStatuses.WAITING_HUMAN_ESCALATION)
        assertThat(afterPhantom.findings.map { it.code }).contains("PHANTOM_SPEC_ARTIFACT")

        // --- Case 2: Unavailable workspace (equipped=false) -> Rejected("WORKSPACE_EVIDENCE_UNAVAILABLE") ---
        val proxyUnavail = mockk<AgentOsProxyClient>(relaxed = true)
        every { proxyUnavail.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to false)
        val capServiceUnavail = CapabilityExecutionService(
            resolver = CapabilityResolver(),
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            attemptRepository = attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentStepResultService = acceptedResults,
            agentOsExecutionAdapter = fakeAdapter,
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = true),
            workspaceDeltaCollector = WorkspaceDeltaCollector(proxyUnavail),
            pluginManager = pluginManager,
            runService = runService,
            transactionManager = transactionManager,
            workEnvironmentRepository = workEnvironmentRepository,
            agentOsWorkspaceIdentityValidator = workspaceValidator,
            worktreeExecutionOccupancyService = WorktreeExecutionOccupancyService(workEnvironmentRepository, worktreeOccupancyRepository, transactionManager = transactionManager),
        )
        val sessionUnavail = SessionRunService(
            repository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            capabilityExecutionService = capServiceUnavail,
            sseHub = sseHub,
            runService = runService,
        )
        // No READY environment preseed.
        val run2 = runService.startRun(
            scope,
            namespace,
            RunStartCommand(runId = "run-unavail", runType = "test-lot2-run", title = "Unavail Test"), actorId = "test-user",
        )
        sessionUnavail.runSession(scope, namespace, "${run2.runId}__f1", java.nio.file.Path.of("."))
        val afterUnavail = runService.getRun(scope, namespace, "run-unavail")!!
        assertThat(afterUnavail.status).isEqualTo(RunStatuses.WAITING_HUMAN_ESCALATION)
        assertThat(afterUnavail.findings.map { it.code }).contains("WORKSPACE_EVIDENCE_UNAVAILABLE")

        // --- Case 3: Valid SPEC.md creation/amendment (shaBefore != shaAfter) -> Accepted & Run COMPLETED ---
        val callCounter = AtomicInteger(0)
        val proxyValid = mockk<AgentOsProxyClient>(relaxed = true)
        every { proxyValid.fetchWorkspaceChanges(any(), any()) } answers {
            val call = callCounter.incrementAndGet()
            if (call == 1) {
                mapOf(
                    "equipped" to true,
                    "status" to "READY",
                    "changes" to mapOf("base" to "aaaa", "files" to emptyList<Any>()),
                )
            } else {
                mapOf(
                    "equipped" to true,
                    "status" to "READY",
                    "changes" to mapOf(
                        "base" to "aaaa",
                        "files" to listOf(
                            mapOf("path" to "SPEC.md", "status" to "ADDED", "exists" to true, "contentSha256" to "new-sha-999"),
                        ),
                    ),
                )
            }
        }
        every { proxyValid.fetchWorkspaceDiff(any(), eq("SPEC.md"), any()) } returns mapOf("patch" to "+## Product\n+Spec content")

        val capServiceValid = CapabilityExecutionService(
            resolver = CapabilityResolver(),
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            attemptRepository = attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentStepResultService = acceptedResults,
            agentOsExecutionAdapter = fakeAdapter,
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = true),
            workspaceDeltaCollector = WorkspaceDeltaCollector(proxyValid),
            pluginManager = pluginManager,
            runService = runService,
            transactionManager = transactionManager,
            workEnvironmentRepository = workEnvironmentRepository,
            agentOsWorkspaceIdentityValidator = workspaceValidator,
            worktreeExecutionOccupancyService = WorktreeExecutionOccupancyService(workEnvironmentRepository, worktreeOccupancyRepository, transactionManager = transactionManager),
        )
        val sessionValid = SessionRunService(
            repository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            capabilityExecutionService = capServiceValid,
            sseHub = sseHub,
            runService = runService,
        )
        // No READY environment preseed.
        val run3 = runService.startRun(
            scope,
            namespace,
            RunStartCommand(runId = "run-valid", runType = "test-lot2-run", title = "Valid Spec Test"), actorId = "test-user",
        )
        sessionValid.runSession(scope, namespace, "${run3.runId}__f1", java.nio.file.Path.of("."))
        val afterValid = runService.getRun(scope, namespace, "run-valid")!!
        assertThat(afterValid.status).isEqualTo(RunStatuses.COMPLETED)
        assertThat(afterValid.domainState["spec_sha256"]).isEqualTo("new-sha-999")
        assertThat(afterValid.domainState["product_rev"]).isEqualTo(1)

        // Verify workspace-delta evidence was recorded on the fragment
        val deltaEvidence = evidenceRepository.list(scope, namespace, "${run3.runId}__f1")
            .filter { it.kind == "workspace-delta" }
        assertThat(deltaEvidence).hasSize(1)
        assertThat(deltaEvidence.single().facts["changedFilesDuringTurn"]).isEqualTo(mapOf("SPEC.md" to "ADDED"))
    }

    private fun insertEnvironment(runId: String) {
        workEnvironmentRepository.insert(
            scope,
            WorkEnvironment(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                environmentId = "$runId-env",
                workUnitId = "$runId-work-unit",
                workflowId = runId,
                namespaceId = namespace,
                parentCaseId = RunService.stableRunRootCaseId(runId),
                repoRoot = worktreeRoot.toString(),
                integrationBranch = "main",
                branch = "factory/$runId",
                worktreePath = worktreeRoot.toString(),
                lifecycleState = WorkEnvironmentState.READY,
                createdBy = "test",
                createdAt = Instant.now(),
                revision = 1,
            ),
        )
    }
}
