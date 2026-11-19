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
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import io.whozoss.factory.run.domain.RunStatuses
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.run.service.RunStartCommand
import io.whozoss.factory.sdk.spi.FactoryFlowCatalogContributor
import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FindingResolution
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.FindingStatus
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.FragmentOutcome
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workspace.RunWorktreeResolver
import io.whozoss.factory.workspace.WorkEnvironmentWorkspaceStateAdapter
import io.whozoss.factory.workspace.WorkspaceProperties
import io.whozoss.factory.workspace.WorkspaceStateWaiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.PluginManager
import org.springframework.beans.factory.annotation.Autowired
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * End-to-end vertical integration test of **Lot 1** (Parent Run + Flow Catalog +
 * Selector `start`/`reduce`/`next` + Loop bounds + Human Checkpoint & Escalation +
 * Shared `rootCaseId` & Worktree + Idempotence).
 */
class RunDrivingLoopIntegrationTest : Neo4jDomainIntegrationTest() {

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
    private lateinit var workEnvironmentRepository: WorkEnvironmentRepository

    @Autowired
    private lateinit var runWorktreeResolver: RunWorktreeResolver

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var worktreeOccupancyRepository: Neo4jWorktreeExecutionOccupancyRepository

    @Autowired
    private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

    @TempDir
    lateinit var fallbackRepoRoot: Path

    @TempDir
    lateinit var sharedWorktreeRoot: Path

    @Autowired lateinit var provisioningIntents: io.whozoss.factory.run.persistence.RunProvisioningIntentRepository
    private val namespace = "4f8a2c10-9e3b-4c5a-8a11-lot1vertical01"

    private fun writeScriptInWorktree(name: String, body: String) {
        val dir = sharedWorktreeRoot.resolve("factory/verification")
        Files.createDirectories(dir)
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n", StandardCharsets.UTF_8)
        check(script.toFile().setExecutable(true)) { "could not mark $script executable" }
    }

    private fun writeManifestInWorktree(vararg entries: Pair<String, String>) {
        val dir = sharedWorktreeRoot.resolve("factory")
        Files.createDirectories(dir)
        val verifications = entries.joinToString(",") { (name, command) -> "\"$name\": { \"command\": \"$command\" }" }
        Files.writeString(
            dir.resolve("verification.json"),
            """{ "schemaVersion": "1", "verifications": { $verifications } }""",
            StandardCharsets.UTF_8,
        )
    }

    /**
     * Test catalog contributing two short fragments:
     * 1. `test.spec-and-approval`:
     *    - TWO parallel entry agent steps (`ux-assess` and `tech-assess`, proving
     *      that `requireSingleEntryAgentStep` is relaxed when the parent Run owns
     *      `rootCaseId`), followed by a `human` approval step (`gate-approval`).
     * 2. `test.implement-and-verify`:
     *    - an `agent` step (`builder`) followed by a `code` step (`verify`) whose
     *      verification target is parameterized via `fragmentInputs.verifications`.
     */
    private val testCatalogContributor = object : FactoryFlowCatalogContributor {
        override fun flows(): List<String> = listOf(
            """
            {
              "schemaVersion": "1",
              "workflowType": "test.spec-and-approval",
              "version": "1.0.0",
              "title": "Test Spec and Human Approval Fragment",
              "steps": [
                {
                  "id": "ux-assess",
                  "name": "UX Assessment",
                  "responsibility": { "kind": "agent", "name": "ForgeUXDesigner" },
                  "dependsOn": []
                },
                {
                  "id": "tech-assess",
                  "name": "Tech Assessment",
                  "responsibility": { "kind": "agent", "name": "ForgeTechDesigner" },
                  "dependsOn": []
                },
                {
                  "id": "gate-approval",
                  "name": "Product & Spec Approval",
                  "responsibility": { "kind": "human", "name": "ProductOwner" },
                  "dependsOn": ["ux-assess", "tech-assess"]
                }
              ]
            }
            """.trimIndent(),
            """
            {
              "schemaVersion": "1",
              "workflowType": "test.implement-and-verify",
              "version": "1.0.0",
              "title": "Test Implement and Parameterized Code Verify Fragment",
              "steps": [
                {
                  "id": "builder",
                  "name": "Builder Step",
                  "responsibility": { "kind": "agent", "name": "ForgeBuilder" },
                  "dependsOn": []
                },
                {
                  "id": "verify",
                  "name": "Code Verification Step",
                  "responsibility": { "kind": "code", "name": "from-inputs" },
                  "dependsOn": ["builder"]
                }
              ]
            }
            """.trimIndent(),
        )
    }

    /**
     * Test selector exercising:
     * - `start` -> `Apply("test.spec-and-approval")` with initial domainState
     * - after `test.spec-and-approval`:
     *   - `reduce` increments `specRevision = 1`
     *   - `next` applies `test.implement-and-verify` with parameterized verification
     * - after `test.implement-and-verify`:
     *   - if FAILED: `reduce` opens a `MAJOR` finding `VERIFY_KO` (if not already open)
     *     and `next` loops on `test.implement-and-verify` with `loopKey = "correction:step-0"`, `maxIterations = 2`
     *   - if COMPLETED: `reduce` resolves open `VERIFY_KO` findings and `next` returns `Complete`
     * - after `factory.escalation` (`ESCALATION_RESOLVED`):
     *   - `next` applies `test.implement-and-verify` targeting `"pass-target"`
     */
    private val testSelector = object : FactoryFlowSelector {
        override fun runTypes(): Set<String> = setOf("test-bmad-v1")
        override fun selectorVersion(): String = "1.2.0"

        override fun start(run: RunView): Pair<RunUpdate, FlowDecision> {
            val init = RunUpdate(
                newDomainState = mapOf(
                    "schemaVersion" to 1,
                    "phase" to "spec",
                    "specRevision" to 0,
                ),
            )
            return init to FlowDecision.Apply(
                flowType = "test.spec-and-approval",
                inputs = mapOf("ticket" to run.ticket),
                reason = "Initial spec and approval fragment",
            )
        }

        override fun reduce(run: RunView, outcome: FragmentOutcome): RunUpdate {
            return when (outcome.flowType) {
                "test.spec-and-approval" -> RunUpdate(
                    newDomainState = run.domainState + mapOf(
                        "phase" to "implement",
                        "specRevision" to 1,
                        "approved" to (outcome.status == WorkflowStatuses.COMPLETED),
                    ),
                )

                "test.implement-and-verify" -> {
                    if (outcome.status == WorkflowStatuses.FAILED) {
                        val alreadyHasOpenFinding = run.findings.any {
                            it.code == "VERIFY_KO" && it.status == FindingStatus.OPEN
                        }
                        RunUpdate(
                            newDomainState = run.domainState + mapOf(
                                "lastAttemptStatus" to "failed",
                                "specRevision" to ((run.domainState["specRevision"] as? Int ?: 1) + 1),
                            ),
                            addedFindings = if (alreadyHasOpenFinding) {
                                emptyList()
                            } else {
                                listOf(
                                    NewFinding(
                                        id = "finding-verify-ko",
                                        code = "VERIFY_KO",
                                        severity = FindingSeverity.MAJOR,
                                        message = "Verification command failed during implement-and-verify",
                                        stepId = "verify",
                                    ),
                                )
                            },
                        )
                    } else {
                        val resolutions = run.findings
                            .filter { it.code == "VERIFY_KO" && it.status == FindingStatus.OPEN }
                            .associate { it.id to FindingResolution(FindingStatus.RESOLVED, "Fixed and verified") }
                        RunUpdate(
                            newDomainState = run.domainState + mapOf("lastAttemptStatus" to "passed"),
                            findingResolutions = resolutions,
                        )
                    }
                }

                "factory.escalation" -> RunUpdate(
                    newDomainState = run.domainState + mapOf("escalatedAndApproved" to true),
                )

                else -> RunUpdate()
            }
        }

        override fun next(run: RunView): FlowDecision {
            val lastFragment = run.fragments.lastOrNull()
            return when (lastFragment?.flowType) {
                "test.spec-and-approval" -> FlowDecision.Apply(
                    flowType = "test.implement-and-verify",
                    inputs = mapOf("verifications" to mapOf("verify" to "fail-target")),
                    reason = "Spec approved; launching step 0 implementation",
                )

                "test.implement-and-verify" -> {
                    if (run.domainState["lastAttemptStatus"] == "passed") {
                        val hasOpenMajor = run.findings.any {
                            it.status == FindingStatus.OPEN &&
                                it.severity in setOf(FindingSeverity.CRITICAL, FindingSeverity.MAJOR)
                        }
                        if (hasOpenMajor) {
                            FlowDecision.EscalateHuman(
                                question = "Open major findings remain",
                                options = listOf("retry", "abort"),
                                reason = "OPEN_FINDINGS_REMAIN",
                            )
                        } else {
                            FlowDecision.Complete(reason = "All steps verified and no open findings")
                        }
                    } else {
                        val target = if (run.domainState["escalatedAndApproved"] == true) "pass-target" else "fail-target"
                        FlowDecision.Apply(
                            flowType = "test.implement-and-verify",
                            inputs = mapOf("verifications" to mapOf("verify" to target)),
                            reason = "Verification failed; scheduling bounded correction",
                            loopKey = "correction:step-0",
                            maxIterations = 2,
                        )
                    }
                }

                else -> FlowDecision.Fail("UNEXPECTED_STATE", "Unexpected fragment state")
            }
        }
    }

    @Test
    fun `vertical slice - parent run shares rootCaseId and worktree across fragments, handles human checkpoint, bounds correction loop to EscalateHuman, survives idempotent replay and completes`() {
        // Prepare verification scripts ONLY in sharedWorktreeRoot (NOT in fallbackRepoRoot!).
        // This proves that code steps in fragments resolve their worktree via the parent RunId.
        writeScriptInWorktree("pass", "exit 0")
        writeScriptInWorktree("fail", "exit 2")
        writeManifestInWorktree(
            "pass-target" to "./factory/verification/pass",
            "fail-target" to "./factory/verification/fail",
        )

        val runId = "run-vertical-lot1"
        val expectedRootCaseId = java.util.UUID.nameUUIDFromBytes(objectMapper.writeValueAsBytes(listOf(
            "factory-run-root-case", scope.organizationId, scope.workstreamId, namespace, runId,
        ))).toString()
        val provisioningProxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { provisioningProxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { provisioningProxy.fetchWorkspaceChanges(any(), any()) } returns mapOf(
            "equipped" to true, "status" to "READY", "path" to sharedWorktreeRoot.toString(),
        )

        // Track every AgentOS case binding observed across all fragments
        val observedBindings = mutableListOf<TrustedCaseBinding>()
        val fakeAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { fakeAdapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = invocation.args[0] as TrustedCaseBinding
            observedBindings += binding
            CaseHandle(caseId = binding.caseId, namespaceId = binding.namespaceId, recovered = false)
        }
        every { fakeAdapter.observeTurnResult(any(), any(), any(), any()) } returns io.whozoss.factory.adapter.agentos.AgentOsObservationResult(
            AgentOsExecutionVerdict.Succeeded(
                outputs = mapOf("summary" to "agent step ok"),
                evidence = mapOf("source" to "test"),
            ),
            null,
        )

        val acceptedResults = mockk<io.whozoss.factory.agentattempt.service.AgentStepResultService>(relaxed = true)
        every { acceptedResults.acceptedResult(any(), any(), any(), any(), any()) } answers {
            val attemptId = invocation.args[4] as String
            io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted(
                type = "result-submitted", resultId = "result-$attemptId", attemptId = attemptId,
                workflowId = invocation.args[2] as String, stepId = invocation.args[3] as String,
                namespaceId = invocation.args[1] as String, caseId = "case-$attemptId", agentName = "test",
                briefHash = "hash", status = io.whozoss.factory.agentattempt.domain.AgentStepResultStatus.PASS,
                summary = "ok", artifacts = emptyList(),
                claims = io.whozoss.factory.agentattempt.domain.AgentStepResultClaim(emptyList()), findings = emptyList(),
                submittedAt = "2026-01-01T00:00:00Z", resultHash = "result-hash-$attemptId",
            )
        }

        val pluginManager = mockk<PluginManager>()
        every { pluginManager.getExtensions(FactoryFlowCatalogContributor::class.java) } returns listOf(testCatalogContributor)
        every { pluginManager.getExtensions(FactoryFlowSelector::class.java) } returns listOf(testSelector)
        every { pluginManager.getExtensions(io.whozoss.factory.sdk.spi.FactoryRunLifecycleObserver::class.java) } returns emptyList()

        val catalog = SessionDefinitionCatalog(objectMapper, pluginManager)
        val runService = RunService(
            runRepository = runRepository,
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            sessionDefinitionCatalog = catalog,
            workspaceProvisioning = io.whozoss.factory.run.service.ParentRunWorkspaceProvisioningService(
                runRepository, workEnvironmentRepository, provisioningIntents, provisioningProxy, objectMapper, transactionManager,
            ),
            transactionManager = transactionManager,
            sessionRunSubmissionService = null,
            agentOsProxyClient = null,
            sseHub = sseHub,
            pluginManager = pluginManager,
        )

        val workspaceProxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { workspaceProxy.fetchWorkspaceChanges(any(), any()) } returns mapOf(
            "equipped" to true,
            "status" to "READY",
            "path" to sharedWorktreeRoot.toString(),
            "changes" to mapOf("files" to emptyList<Any>()),
        )

        val capabilityExecutionService = CapabilityExecutionService(
            resolver = CapabilityResolver(),
            workflowRepository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            attemptRepository = attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentStepResultService = acceptedResults,
            agentOsExecutionAdapter = fakeAdapter,
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = true),
            workEnvironmentRepository = workEnvironmentRepository,
            agentOsWorkspaceIdentityValidator = AgentOsWorkspaceIdentityValidator(workspaceProxy),
            worktreeExecutionOccupancyService = WorktreeExecutionOccupancyService(
                workEnvironmentRepository,
                worktreeOccupancyRepository,
                transactionManager = transactionManager,
            ),
        )

        val enabledWorktreeResolver = RunWorktreeResolver(
            WorkspaceStateWaiter(
                statePort = WorkEnvironmentWorkspaceStateAdapter(workEnvironmentRepository),
                properties = WorkspaceProperties(enabled = true),
            ),
        )

        val sessionRunService = SessionRunService(
            repository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            capabilityExecutionService = capabilityExecutionService,
            sseHub = sseHub,
            runWorktreeResolver = enabledWorktreeResolver,
            runService = runService,
        )

        val workflowService = WorkflowService(
            repository = workflowRepository,
            evidenceRepository = evidenceRepository,
            interactionRepository = interactionRepository,
            sseHub = sseHub,
            sessionRunService = sessionRunService,
            pluginManager = pluginManager,
        )

        // 1. Start the parent Run (and verify startRun idempotency on replay)
        val started = runService.startRun(
            actorId = "test-user",
            scope = scope,
            namespaceId = namespace,
            command = RunStartCommand(
                runId = runId,
                runType = "test-bmad-v1",
                title = "Vertical Lot 1 Run",
                ticket = "WZ-101",
            ),
            repoRoot = fallbackRepoRoot.toString(),
        )
        val replayedStart = runService.startRun(
            actorId = "test-user",
            scope = scope,
            namespaceId = namespace,
            command = RunStartCommand(
                runId = runId,
                runType = "test-bmad-v1",
                title = "Vertical Lot 1 Run",
                ticket = "WZ-101",
            ),
            repoRoot = fallbackRepoRoot.toString(),
        )
        assertThat(replayedStart.fragments).hasSize(1)
        assertThat(started.rootCaseId).isEqualTo(expectedRootCaseId)
        assertThat(started.domainRevision).isEqualTo(1L)

        val fragment1Id = started.fragments.single().fragmentId
        assertThat(fragment1Id).isEqualTo("${runId}__f1")

        // 2. Run Fragment #1 (`test.spec-and-approval`):
        //    - Two parallel entry agent steps (`ux-assess`, `tech-assess`) succeed under `expectedRootCaseId`
        //    - Suspends in `WAITING_HUMAN` on `gate-approval`
        val f1FirstPass = sessionRunService.runSession(scope, namespace, fragment1Id, fallbackRepoRoot, "WZ-101")
        assertThat(f1FirstPass.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)

        val runDuringHumanWait = runService.getRun(scope, namespace, runId)!!
        assertThat(runDuringHumanWait.status).isEqualTo(RunStatuses.WAITING_HUMAN)
        assertThat(runDuringHumanWait.fragments).hasSize(1)

        // 3. Answer the fragment-level human checkpoint (`gate-approval`):
        //    - `replyInteraction` closes the checkpoint and auto-resumes `SessionRunService`
        //    - Fragment #1 completes -> `RunService` runs `reduce` + `next` -> creates Fragment #2
        //    - Fragment #2 (`test.implement-and-verify` with `fail-target`) runs and fails
        //    - `RunService` loops (`correction:step-0` #1 -> Fragment #3, #2 -> Fragment #4)
        //    - On the 3rd failure, `loopCounts["correction:step-0"] == 3 > maxIterations(2)` ->
        //      automatically substitutes `EscalateHuman`!
        val humanInteraction = interactionRepository.list(scope, namespace, fragment1Id, openOnly = true).single()
        workflowService.replyInteraction(
            scope = scope,
            namespaceId = namespace,
            workflowId = fragment1Id,
            interactionId = humanInteraction.interactionId,
            expectedRevision = humanInteraction.revision,
            actionId = "approve",
            text = "Spec approved",
            actorId = "po-user",
            repoRoot = fallbackRepoRoot,
        )

        val runAtEscalation = runService.getRun(scope, namespace, runId)!!
        assertThat(runAtEscalation.status).isEqualTo(RunStatuses.WAITING_HUMAN_ESCALATION)
        assertThat(runAtEscalation.activeEscalation).isNotNull
        val escalation = requireNotNull(runAtEscalation.activeEscalation)
        assertThat(escalation.reason).contains("LOOP_BOUND_EXCEEDED")
        // Fragments so far:
        // #1: spec-and-approval (completed)
        // #2: initial implement-and-verify (failed)
        // #3: correction iter 1 (failed)
        // #4: correction iter 2 (failed) -> triggered loop bound exceeded on iter 3!
        assertThat(runAtEscalation.fragments).hasSize(4)

        // Verify D10: `specRevision` incremented on every failed attempt, yet the MAJOR finding
        // `VERIFY_KO` opened in Fragment #2 is STILL OPEN!
        assertThat(runAtEscalation.domainRevision).isGreaterThan(2L)
        assertThat(runAtEscalation.findings).hasSize(1)
        assertThat(runAtEscalation.findings.single().code).isEqualTo("VERIFY_KO")
        assertThat(runAtEscalation.findings.single().status).isEqualTo(FindingStatus.OPEN)

        // 4. Verify idempotency of fragment completion replay:
        //    Re-notifying `onFragmentSessionUpdated` for an already-decided fragment is a no-op!
        val beforeReplayRevision = runAtEscalation.revision
        val afterDuplicateNotification = runService.onFragmentSessionUpdated(
            scope = scope,
            namespaceId = namespace,
            fragmentWorkflowId = "${runId}__f4",
            sessionResult = sessionRunService.sessionState(scope, namespace, "${runId}__f4")!!,
            repoRoot = fallbackRepoRoot,
            sessionRunService = sessionRunService,
        )!!
        assertThat(afterDuplicateNotification.revision).isEqualTo(beforeReplayRevision)
        assertThat(afterDuplicateNotification.fragments).hasSize(4)

        // 5. Resolve the run-level human escalation (`replyRunEscalation` with `"retry"`):
        //    - Resets loop counters
        //    - Selector schedules Fragment #5 (`test.implement-and-verify` with `pass-target`)
        //    - Code step resolves `sharedWorktreeRoot` via `RunWorktreeResolver` and passes
        //    - `reduce` marks finding `VERIFY_KO` as `RESOLVED`
        //    - `next` sees no open MAJOR/CRITICAL findings and completes the Run!
        val finalRun = runService.replyRunEscalation(
            scope = scope,
            namespaceId = namespace,
            runId = runId,
            escalationId = escalation.escalationId,
            selectedOption = "retry",
            text = "Fixed verification target, please retry",
            actorId = "tech-lead",
            repoRoot = fallbackRepoRoot,
            sessionRunService = sessionRunService,
        )

        assertThat(finalRun.status)
            .withFailMessage("Expected COMPLETED but was ${finalRun.status} (escalation: ${finalRun.activeEscalation}, fragments: ${finalRun.fragments}, domainState: ${finalRun.domainState})")
            .isEqualTo(RunStatuses.COMPLETED)
        assertThat(finalRun.activeEscalation).isNull()
        assertThat(finalRun.fragments).hasSize(5)
        assertThat(finalRun.fragments.last().fragmentId).isEqualTo("${runId}__f5")
        assertThat(finalRun.fragments.last().status).isEqualTo(WorkflowStatuses.COMPLETED)

        // Verify the finding is now explicitly RESOLVED
        assertThat(finalRun.findings).hasSize(1)
        assertThat(finalRun.findings.single().status).isEqualTo(FindingStatus.RESOLVED)

        // Verify ALL agent steps across ALL 5 fragments shared the exact same parent Run rootCaseId
        // and bound to the parent Run's WorkEnvironment!
        assertThat(observedBindings).isNotEmpty
        assertThat(observedBindings.map { it.parentCaseId }).containsOnly(expectedRootCaseId)
        assertThat(observedBindings.map { it.environmentRef }).containsOnly(workEnvironmentRepository.findLatestByWorkflowId(scope, runId)!!.environmentId)
    }
}
