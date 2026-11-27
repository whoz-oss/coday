package io.whozoss.factory.workflow

import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.AgentOsObservationResult
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.adapter.agentos.TrustedCaseBinding
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsAdapterProperties
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.capability.AgentTurnCapability
import io.whozoss.factory.capability.AgentTurnRequest
import io.whozoss.factory.capability.AgentTurnResult
import io.whozoss.factory.capability.CapabilityExecution
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.capability.NeedsResearchRouter
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.oracle.domain.OracleApplicableCondition
import io.whozoss.factory.oracle.domain.OracleDefinition
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.registry.OracleDefinitionRegistry
import io.whozoss.factory.oracle.service.OracleExecutionService
import io.whozoss.factory.oracle.service.OracleRunResult
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests of the W8.3 session DAG sequencer.
 *
 * Extends the shared [Neo4jDomainIntegrationTest] fixture (one Spring context, one
 * in-process embedded Neo4j) — no new `@SpringBootTest` variant. `code` steps run
 * deterministic shell fixtures; `agent` steps use a fake [AgentTurnCapability]
 * (never a real AgentOS). Covers a linear DAG, parallel independent branches with
 * a failure + blocked propagation, a human suspension/resume, and the agent-turn
 * failure rule.
 */
class SessionSequencerIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var sessionRunService: SessionRunService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @Autowired
    private lateinit var attemptRepository: AgentStepAttemptRepository

    @Autowired
    private lateinit var durableAttempts: DurableAgentAttemptService

    @Autowired
    private lateinit var questions: AgentStepQuestionService

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-sequencer-w83"

    // ----- fixtures ------------------------------------------------------

    private fun stepJson(id: String, kind: String, name: String, dependsOn: List<String>): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to "Step $id",
            "responsibility" to mapOf("kind" to kind, "name" to name),
            "dependsOn" to dependsOn,
        )

    private fun writeScript(name: String, body: String) {
        val dir = repoRoot.resolve("factory/verification")
        Files.createDirectories(dir)
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n", StandardCharsets.UTF_8)
        check(script.toFile().setExecutable(true)) { "could not mark $script executable" }
    }

    private fun writeManifest(vararg entries: Pair<String, String>) {
        val dir = repoRoot.resolve("factory")
        Files.createDirectories(dir)
        val verifications = entries.joinToString(",") { (name, command) -> "\"$name\": { \"command\": \"$command\" }" }
        Files.writeString(
            dir.resolve("verification.json"),
            """{ "schemaVersion": "1", "verifications": { $verifications } }""",
            StandardCharsets.UTF_8,
        )
    }

    private fun startSession(workflowType: String, workflowId: String, steps: List<Map<String, Any?>>, ticket: String? = null) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Session $workflowType",
            "steps" to steps,
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = workflowType,
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(valid.definition),
                definition = valid.definition,
            ),
        )
        val result = workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = workflowType, title = "Session $workflowType", ticket = ticket),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
        assertThat(result.status).isEqualTo(201)
    }

    private fun statusOf(workflowId: String, stepId: String): String =
        workflowRepository.findStepStates(scope, namespace, workflowId).first { it.stepId == stepId }.status

    private fun answerHuman(workflowId: String, stepId: String, actionId: String) {
        val interaction = interactionRepository.list(scope, namespace, workflowId, openOnly = false)
            .filter { it.stepId == stepId }
            .maxByOrNull { it.revision }!!
        interactionRepository.update(
            scope,
            namespace,
            workflowId,
            interaction.interactionId,
            interaction.revision,
            interaction.copy(
                status = "closed",
                revision = interaction.revision + 1,
                payload = interaction.payload + ("response" to mapOf("actionId" to actionId)),
            ),
        )
    }

    private fun agentSessionService(result: AgentTurnResult): SessionRunService =
        SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            CapabilityExecutionService(
                CapabilityResolver(
                    object : AgentTurnCapability {
                        override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult = result
                    },
                ),
                workflowRepository,
                evidenceRepository,
                interactionRepository,
                attemptRepository,
                durableAgentAttemptService = mockk<DurableAgentAttemptService>(relaxed = true),
                agentOsExecutionAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true),
                agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            ),
            sseHub,
        )

    /**
     * Lot E end-to-end: a `NEEDS_RESEARCH` verdict blocks the step (its dependants
     * are not launched), automatically runs a Searcher attempt, then re-arms the
     * step as a new attempt on the same worktree — WITHOUT sealing the run as a
     * terminal `FAILED`.
     */
    @Test
    fun `NEEDS_RESEARCH routes a Searcher then re-arms without sealing the run`() {
        val workflowId = "wf-needs-research"
        startSession("nr-dag", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))

        val observedStepIds = mutableListOf<String>()
        val firstCall = AtomicBoolean(true)
        val capability = mockk<CapabilityExecutionService>()
        every { capability.attemptsOf(any(), any(), any()) } returns emptyList()
        every { capability.userCancelledAttemptId(any(), any(), any(), any(), any()) } returns null
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
            val step = invocation.args[3] as WorkflowStepDefinition
            observedStepIds += step.id
            if (step.id == "s1" && firstCall.getAndSet(false)) {
                CapabilityExecution(
                    outcome = CapabilityOutcome.AgentNeedsResearch(
                        stepId = "s1",
                        persona = "architect",
                        attemptId = "attempt-1",
                        resultId = "result-1",
                        summary = "missing upstream contract",
                        findings = listOf(mapOf("severity" to "blocking", "code" to "MISSING")),
                    ),
                    attemptId = "attempt-1",
                )
            } else {
                CapabilityExecution(
                    outcome = CapabilityOutcome.AgentCompleted(step.id, step.responsibility.name, "PASS", emptyMap()),
                    attemptId = "attempt-${step.id}",
                )
            }
        }
        val router = NeedsResearchRouter(mockk<DurableAgentAttemptService>(relaxed = true), evidenceRepository)
        val service = SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            capability,
            sseHub,
            oracleDefinitionRegistry = null,
            oracleExecutionService = null,
            transactionManager = null,
            needsResearchRouter = router,
        )

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        // The run is NOT sealed as a terminal failure.
        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        // A Searcher attempt ran, and the initial step was re-executed.
        assertThat(observedStepIds).contains("s1", NeedsResearchRouter.searcherStepId("s1"))
        assertThat(observedStepIds.count { it == "s1" }).isGreaterThanOrEqualTo(2)
        // The original NEEDS_RESEARCH proof is preserved.
        val proof = evidenceRepository.list(scope, namespace, workflowId, "s1")
            .filter { it.kind == NeedsResearchRouter.NEEDS_RESEARCH_EVIDENCE_KIND }
        assertThat(proof).hasSize(1)
        assertThat(proof.single().outcome).isEqualTo(NeedsResearchRouter.NEEDS_RESEARCH_OUTCOME)
    }

    @Test
    fun `non-routing queryUser waits for agent completion then resumes from the finalized attempt`() {
        val workflowId = "wf-query-user-resume"
        startSession("query-user-resume", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))
        val capability = mockk<CapabilityExecutionService>()
        every { capability.attemptsOf(any(), any(), any()) } returns emptyList()
        every { capability.userCancelledAttemptId(any(), any(), any(), any(), any()) } returns null
        every { capability.canResumeWaitingAgentStep(scope, namespace, workflowId, "s1") } returns false
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns
            CapabilityExecution(CapabilityOutcome.HumanCheckpointRequired("s1", "architect"), attemptId = "attempt-1")
        val service = SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub)

        assertThat(service.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        // A repeated continuation while queryUser is still unanswered cannot approve an agent step.
        assertThat(service.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.WAITING_HUMAN)

        // The answer resumes the same agent turn. Its eventual durable finalization, not a human
        // checkpoint approval, permits the outbox continuation to replay the outcome.
        every { capability.canResumeWaitingAgentStep(scope, namespace, workflowId, "s1") } returns true
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns
            CapabilityExecution(CapabilityOutcome.AgentCompleted("s1", "architect", "PASS", emptyMap()), attemptId = "attempt-1")
        assertThat(service.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        verify(exactly = 2) { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(interactionRepository.list(scope, namespace, workflowId, false)).isEmpty()
    }

    @Test
    fun `waiting resolution re-arms an agent that became resumable after the initial check`() {
        val workflowId = "wf-waiting-recheck"
        startSession("waiting-recheck", workflowId, listOf(stepJson("s1", "agent", "architect", emptyList())))
        val capability = mockk<CapabilityExecutionService>()
        every { capability.attemptsOf(any(), any(), any()) } returns emptyList()
        every { capability.userCancelledAttemptId(any(), any(), any(), any(), any()) } returns null
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns
            CapabilityExecution(CapabilityOutcome.HumanCheckpointRequired("s1", "architect"), attemptId = "attempt")
        val runner = SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub)
        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        every { capability.canResumeWaitingAgentStep(scope, namespace, workflowId, "s1") } returnsMany listOf(false, true)
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } returns
            CapabilityExecution(CapabilityOutcome.AgentCompleted("s1", "architect", "PASS", emptyMap()), attemptId = "successor")

        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        verify(exactly = 2) { capability.canResumeWaitingAgentStep(scope, namespace, workflowId, "s1") }
        verify(exactly = 0) { capability.hasFinalizedAttempt(any(), any(), any(), any()) }
    }

    @Test
    fun `answering an agent question registers successor attempt N+1 and resumes the agent step and its dependents`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-question-successor"
        startSession("question-successor", workflowId, listOf(
            stepJson("s1", "agent", "architect", emptyList()),
            stepJson("s2", "code", "check", listOf("s1")),
        ))
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = firstArg<TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        var answered = false
        every { adapter.observeTurnResult(any(), any(), any(), any()) } answers {
            AgentOsObservationResult(if (answered) AgentOsExecutionVerdict.Succeeded(mapOf("summary" to "done"))
                else AgentOsExecutionVerdict.WaitingHuman("question"))
        }
        val capability = CapabilityExecutionService(CapabilityResolver(), workflowRepository, evidenceRepository,
            interactionRepository, attemptRepository, durableAttempts, adapter)
        val runner = SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub)

        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.PENDING)
        val predecessor = durableAttempts.findLatestForStep(scope, namespace, workflowId, "s1")!!
        assertThat(predecessor.status).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        // Persist the legacy channel's question fixture; use the real transactional answer
        // service to close it and atomically supersede/register N+1. No recovery scanner runs.
        val interaction = interactionRepository.insert(scope, HumanInteractionRecord(
            "successor-question", namespace, workflowId, "s1", "agent_question", "waiting", 1,
            mapOf("attemptId" to predecessor.attemptId, "prompt" to "Proceed?", "questionType" to "FREE_TEXT"),
        ))
        val answer = questions.answer(scope, namespace, workflowId, interaction.interactionId, interaction.revision, "Proceed", "alice")
        val successor = durableAttempts.findLatestForStep(scope, namespace, workflowId, "s1")!!
        assertThat(successor.attemptId).isEqualTo(answer.successorAttemptId)
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(durableAttempts.find(scope, namespace, workflowId, "s1", predecessor.attemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.SUPERSEDED)
        assertThat(interactionRepository.find(scope, namespace, workflowId, interaction.interactionId)!!.status).isEqualTo("closed")
        answered = true

        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(durableAttempts.find(scope, namespace, workflowId, "s1", successor.attemptId)!!.status)
            .isEqualTo(AgentAttemptStatus.SUCCEEDED)
        verify(exactly = 1) { adapter.startTurn(match<TrustedCaseBinding> { it.attemptId == successor.attemptId }, any(), any()) }
        val evidenceCount = evidenceRepository.list(scope, namespace, workflowId).size
        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(evidenceRepository.list(scope, namespace, workflowId)).hasSize(evidenceCount)
    }

    // ----- tests ---------------------------------------------------------

    @Autowired lateinit var environments: io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
    @Autowired lateinit var transactions: org.springframework.transaction.PlatformTransactionManager
    @Autowired lateinit var occupations: io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
    @Autowired lateinit var stepResults: io.whozoss.factory.agentattempt.service.AgentStepResultService
    @Autowired lateinit var objectMapper: com.fasterxml.jackson.databind.ObjectMapper

    private fun gitAt(path: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", path.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { output }
        return output.trim()
    }

    private fun realWorkspace(): Pair<Path, String> {
        val source = Files.createDirectory(repoRoot.resolve("source"))
        gitAt(source, "init", "-b", "main")
        gitAt(source, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "baseline")
        val workspace = repoRoot.resolve("adopted")
        gitAt(source, "worktree", "add", "-b", "factory/test", workspace.toString())
        Files.createDirectories(workspace.resolve("factory"))
        Files.writeString(workspace.resolve("factory/verification.json"),
            """{"schemaVersion":"1","verifications":{"check":{"command":"pwd > code-cwd"}}}""")
        return workspace to gitAt(workspace, "rev-parse", "HEAD")
    }

    private fun adoptedSession(
        workflowId: String,
        proxy: io.whozoss.factory.proxy.AgentOsProxyClient,
        adapter: AgentOsExecutionAdapter,
        afterPreparation: () -> Unit = {},
    ): SessionRunService {
        val provisioning = io.whozoss.factory.workspace.StandaloneWorkspaceProvisioningService(environments, proxy,
            transactions, io.whozoss.factory.run.service.RunProvisioningTiming(0, 1), io.whozoss.factory.workspace.WorkspaceProperties())
        val capability = io.mockk.spyk(CapabilityExecutionService(CapabilityResolver(), workflowRepository, evidenceRepository,
            interactionRepository, attemptRepository, durableAttempts, adapter,
            agentStepResultService = stepResults,
            transactionManager = transactions, workEnvironmentRepository = environments,
            agentOsWorkspaceIdentityValidator = io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator(proxy),
            worktreeExecutionOccupancyService = io.whozoss.factory.worktree.WorktreeExecutionOccupancyService(environments, occupations, transactionManager = transactions),
            standaloneWorkspaceProvisioning = provisioning))
        every { capability.prepareAgentWorkspace(any(), any(), any(), any()) } answers {
            callOriginal()
            afterPreparation()
        }
        // Fake the remote worker only: submit through the real result service, then let the
        // production finalization and accepted-result release hook fence/release the occupation.
        every { adapter.observeTurnResult(any(), any(), any(), any()) } answers {
            val token = firstArg<io.whozoss.factory.adapter.agentos.TurnToken>()
            val attempt = durableAttempts.findLatestForStep(scope, namespace, workflowId,
                if (token.attemptId == CapabilityExecutionService.stableAttemptId(workflowId, "entry")) "entry" else "child")!!
            stepResults.submitTrusted(scope, attempt,
                objectMapper.readTree("""{"status":"PASS","summary":"done","claims":{"modifiedFiles":[]}}"""),
                attempt.agentName, "test-result:${attempt.attemptId}")
            AgentOsObservationResult(AgentOsExecutionVerdict.Succeeded(emptyMap()))
        }
        return SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub,
            transactionManager = transactions,
            runWorktreeResolver = io.whozoss.factory.workspace.RunWorktreeResolver(io.whozoss.factory.workspace.WorkspaceStateWaiter(
                io.whozoss.factory.workspace.WorkEnvironmentWorkspaceStateAdapter(environments))))
    }

    private fun standaloneStart(id: String, child: Boolean = false) {
        startSession(id, id, buildList {
            add(stepJson("entry", "agent", "Writer", emptyList()))
            if (child) add(stepJson("child", "agent", "Reviewer", listOf("entry")))
            add(stepJson("build", "code", "check", listOf(if (child) "child" else "entry")))
        })
        val instance = workflowRepository.findInstance(scope, namespace, id)!!
        check(workflowRepository.updateInstance(scope, namespace, id, instance.revision, instance.copy(
            revision = instance.revision + 1,
            instance = instance.instance + mapOf("controllerRequest" to mapOf("actorId" to "alice"),
                "controllerExecution" to mapOf("actorId" to "spoofed-body-actor")))))
    }

    @Test fun `standalone adopts real root before agent and child then code uses same cwd and replay is inert`() {
        val (workspace, base) = realWorkspace()
        val id = "standalone-real-adoption"
        standaloneStart(id, child = true)
        val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), namespace, any(), "alice") } answers {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(environments.findLatestByWorkflowId(scope, id)!!.lifecycleState)
                .isIn(io.whozoss.factory.environment.domain.WorkEnvironmentState.PROVISIONING, io.whozoss.factory.environment.domain.WorkEnvironmentState.READY)
        }
        every { proxy.fetchWorkspaceChanges(any(), "alice") } returns
            mapOf("equipped" to true, "status" to "READY", "path" to workspace.toString(), "changes" to mapOf("base" to base))
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = firstArg<TrustedCaseBinding>()
            val environment = environments.findLatestByWorkflowId(scope, id)!!
            assertThat(binding.environmentRef).isEqualTo(environment.environmentId)
            assertThat(environment.worktreePath).isEqualTo(workspace.toRealPath().toString())
            assertThat(binding.externalUserId).isEqualTo("alice")
            if (invocation.args[2] == "child") assertThat(binding.parentCaseId).isEqualTo(environment.parentCaseId)
            CaseHandle(binding.caseId, namespace, true)
        }
        val runner = adoptedSession(id, proxy, adapter)
        assertThat(runner.runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        val environment = environments.findLatestByWorkflowId(scope, id)!!
        assertThat(environment.parentCaseId).isEqualTo(workflowRepository.findInstance(scope, namespace, id)!!.rootCaseId)
        assertThat(environment.baseCommit).isEqualTo(base)
        assertThat(Files.readString(workspace.resolve("code-cwd")).trim()).isEqualTo(workspace.toRealPath().toString())
        val revision = environment.revision
        val count = evidenceRepository.list(scope, namespace, id).size
        assertThat(runner.runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(environments.findLatestByWorkflowId(scope, id)!!.revision).isEqualTo(revision)
        assertThat(evidenceRepository.list(scope, namespace, id)).hasSize(count)
        verify(exactly = 2) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `explicit disabled workspace allows polling with provisioning bean present`() {
        val id = "standalone-disabled-polling"
        standaloneStart(id)
        val provisioning = io.whozoss.factory.workspace.StandaloneWorkspaceProvisioningService(environments,
            mockk(), transactions, io.whozoss.factory.run.service.RunProvisioningTiming(0, 1),
            io.whozoss.factory.workspace.WorkspaceProperties(enabled = false))
        var calls = 0
        val resolver = CapabilityResolver(object : AgentTurnCapability {
            override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                calls++
                return AgentTurnResult.Completed("PASS")
            }
        })
        val capability = CapabilityExecutionService(resolver, workflowRepository, evidenceRepository, interactionRepository,
            attemptRepository, durableAttempts, mockk(relaxed = true),
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false), standaloneWorkspaceProvisioning = provisioning)
        val step = WorkflowStepDefinition("entry", "Entry", io.whozoss.factory.workflow.domain.WorkflowStepResponsibility(
            io.whozoss.factory.workflow.domain.ResponsibilityKind.AGENT, "Writer"), emptyList())
        capability.prepareAgentWorkspace(scope, namespace, id, step)
        assertThat(capability.resolveAndRecord(scope, namespace, id, step, repoRoot).outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        assertThat(calls).isEqualTo(1)
        assertThat(environments.findLatestByWorkflowId(scope, id)).isNull()
    }

    @Test fun `enabled workspace with disabled adapter fails before root reservation or provisioning`() {
        val id = "standalone-refused-polling"
        standaloneStart(id)
        val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        val provisioning = io.whozoss.factory.workspace.StandaloneWorkspaceProvisioningService(environments, proxy,
            transactions, io.whozoss.factory.run.service.RunProvisioningTiming(0, 1),
            io.whozoss.factory.workspace.WorkspaceProperties())
        val capability = CapabilityExecutionService(CapabilityResolver(), workflowRepository, evidenceRepository,
            interactionRepository, attemptRepository, durableAttempts, adapter,
            agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false), standaloneWorkspaceProvisioning = provisioning)
        val runner = SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub,
            transactionManager = transactions)
        assertThat(runner.runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(workflowRepository.findInstance(scope, namespace, id)!!.rootCaseId).isNull()
        assertThat(environments.findLatestByWorkflowId(scope, id)).isNull()
        assertThat(durableAttempts.findLatestForStep(scope, namespace, id, "entry")).isNull()
        verify(exactly = 0) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(), any(), any()) }
        verify(exactly = 0) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `relative root invariant fails durably through sequencer and replay never provisions again`() {
        val id = "standalone-relative-root"
        standaloneStart(id)
        val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), namespace, any(), "alice") } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), "alice") } returns
            mapOf("equipped" to true, "status" to "READY", "path" to "relative/root")
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        repeat(2) {
            assertThat(adoptedSession(id, proxy, adapter).runSession(scope, namespace, id, repoRoot).status)
                .isEqualTo(WorkflowStatuses.FAILED)
        }
        assertThat(environments.findLatestByWorkflowId(scope, id)!!.provisioningFailure).isEqualTo("ROOT_WORKSPACE_ADOPTION_FAILED")
        assertThat(durableAttempts.findLatestForStep(scope, namespace, id, "entry")).isNull()
        verify(exactly = 1) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `body-only actor cannot provision standalone workspace`() {
        val id = "standalone-no-authenticated-actor"
        standaloneStart(id)
        val instance = workflowRepository.findInstance(scope, namespace, id)!!
        check(workflowRepository.updateInstance(scope, namespace, id, instance.revision,
            instance.copy(revision = instance.revision + 1, instance = instance.instance - "controllerRequest")))
        val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        assertThat(adoptedSession(id, proxy, adapter).runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        verify(exactly = 0) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `pre-claim crash after READY adoption resumes through a new sequencer without losing baseline`() {
        val (workspace, base) = realWorkspace()
        val id = "standalone-preclaim-crash"
        standaloneStart(id)
        val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
        val rootCase = CapabilityExecutionService.stableCaseId(id, "entry")
        every { proxy.ensureRunRootCase(rootCase, namespace, any(), "alice") } returns Unit
        every { proxy.fetchWorkspaceChanges(rootCase, "alice") } returns
            mapOf("equipped" to true, "status" to "READY", "path" to workspace.toString())
        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val b = firstArg<TrustedCaseBinding>()
            CaseHandle(b.caseId, namespace, true)
        }
        org.assertj.core.api.Assertions.assertThatThrownBy {
            adoptedSession(id, proxy, adapter) { throw AssertionError("process crash after adoption") }
                .runSession(scope, namespace, id, repoRoot)
        }.isInstanceOf(AssertionError::class.java)
        assertThat(statusOf(id, "entry")).isEqualTo(WorkflowStatuses.READY)
        assertThat(durableAttempts.findLatestForStep(scope, namespace, id, "entry")).isNull()
        val adopted = environments.findLatestByWorkflowId(scope, id)!!
        assertThat(adopted.baseCommit).isEqualTo(base)
        assertThat(adoptedSession(id, proxy, adapter).runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(environments.findLatestByWorkflowId(scope, id)).isEqualTo(adopted)
        verify(exactly = 1) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    @Test fun `transient adoption failures and interruption leave READY without attempt and resume through sequencer`() {
        val (workspace, _) = realWorkspace()
        listOf("absent", "preparing", "network", "ready-error", "interrupt").forEach { scenario ->
            val id = "standalone-transient-$scenario"
            standaloneStart(id)
            val rootCase = CapabilityExecutionService.stableCaseId(id, "entry")
            val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
            every { proxy.ensureRunRootCase(rootCase, namespace, any(), "alice") } returns Unit
            every { proxy.fetchWorkspaceChanges(rootCase, "alice") } answers {
                when (scenario) {
                    "absent" -> null
                    "preparing" -> mapOf("equipped" to true, "status" to "PREPARING")
                    "ready-error" -> mapOf("equipped" to true, "status" to "READY", "error" to "Retry shortly")
                    "interrupt" -> throw InterruptedException("interrupted HTTP")
                    else -> throw io.whozoss.factory.proxy.AgentOsUnavailableException("network", java.io.IOException("connection reset"))
                }
            }
            val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
            every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
                val b = firstArg<TrustedCaseBinding>()
                assertThat(b.externalUserId).isEqualTo("alice")
                CaseHandle(b.caseId, namespace, true)
            }
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy {
                    adoptedSession(id, proxy, adapter).runSession(scope, namespace, id, repoRoot)
                }.isInstanceOf(if (scenario == "interrupt") InterruptedException::class.java else
                    io.whozoss.factory.workspace.TransientWorkspaceAdoptionException::class.java)
                assertThat(Thread.currentThread().isInterrupted).isEqualTo(scenario == "interrupt")
            } finally { Thread.interrupted() }
            assertThat(statusOf(id, "entry")).isEqualTo(WorkflowStatuses.READY)
            assertThat(durableAttempts.findLatestForStep(scope, namespace, id, "entry")).isNull()
            val pending = environments.findLatestByWorkflowId(scope, id)!!
            assertThat(pending.provisioningFailure).isNull()
            verify(exactly = 0) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
            every { proxy.fetchWorkspaceChanges(rootCase, "alice") } returns
                mapOf("equipped" to true, "status" to "READY", "path" to workspace.toString())
            assertThat(adoptedSession(id, proxy, adapter).runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.COMPLETED)
            assertThat(environments.findLatestByWorkflowId(scope, id)!!.environmentId).isEqualTo(pending.environmentId)
            verify(exactly = 1) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
        }
    }

    @Test fun `explicit failed standalone workspace persists failure with no dispatch or automatic retry`() {
        listOf("FAILED").forEachIndexed { index, status ->
            val id = "standalone-adoption-failure-$index"
            standaloneStart(id)
            val proxy = mockk<io.whozoss.factory.proxy.AgentOsProxyClient>()
            every { proxy.ensureRunRootCase(any(), any(), any(), "alice") } returns Unit
            every { proxy.fetchWorkspaceChanges(any(), "alice") } returns status?.let { mapOf("equipped" to true, "status" to it) }
            val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
            val runner = adoptedSession(id, proxy, adapter)
            assertThat(runner.runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
            assertThat(environments.findLatestByWorkflowId(scope, id)!!.provisioningFailure).isEqualTo("ROOT_WORKSPACE_ADOPTION_FAILED")
            val count = evidenceRepository.list(scope, namespace, id).size
            assertThat(runner.runSession(scope, namespace, id, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
            assertThat(evidenceRepository.list(scope, namespace, id)).hasSize(count)
            verify(exactly = 1) { proxy.fetchWorkspaceChanges(any(), any()) }
            verify(exactly = 0) { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
        }
    }

    @Test
    fun `standalone missing environment failure is durable and replay does not recheck workspace`() {
        val workflowId = "wf-strict-workspace"
        startSession("strict-workspace", workflowId, listOf(stepJson("build", "code", "build", emptyList())))
        var reads = 0
        val port = object : io.whozoss.factory.workspace.WorkspaceStatePort {
            override fun latestForWorkflow(scope: io.whozoss.factory.persistence.TenantScope, workflowId: String): io.whozoss.factory.workspace.WorkspaceSnapshot? {
                reads++
                return null
            }
        }
        val capability = mockk<CapabilityExecutionService>()
        val runner = SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub,
            runWorktreeResolver = io.whozoss.factory.workspace.RunWorktreeResolver(io.whozoss.factory.workspace.WorkspaceStateWaiter(port)))

        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "build")).isEqualTo(WorkflowStatuses.FAILED)
        val failures = evidenceRepository.list(scope, namespace, workflowId).filter { it.outcome == WorkflowStatuses.FAILED }
        assertThat(failures).hasSize(1)
        assertThat(failures.single().facts["message"].toString()).contains("CODE_WORKSPACE_REQUIRED")
        assertThat(failures.single().facts["executed"]).isEqualTo(false)
        val evidenceCount = evidenceRepository.list(scope, namespace, workflowId).size
        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(reads).isEqualTo(1)
        assertThat(evidenceRepository.list(scope, namespace, workflowId)).hasSize(evidenceCount)
        verify(exactly = 0) { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `code binding sees root case reserved by entry agent in the same session`() {
        val workflowId = "wf-refreshed-root"
        startSession("refreshed-root", workflowId, listOf(
            stepJson("entry", "agent", "architect", emptyList()),
            stepJson("build", "code", "build", listOf("entry")),
        ))
        val capability = mockk<CapabilityExecutionService>()
        every { capability.prepareAgentWorkspace(any(), any(), any(), any()) } returns Unit
        every { capability.attemptsOf(any(), any(), any()) } returns emptyList()
        every { capability.userCancelledAttemptId(any(), any(), any(), any(), any()) } returns null
        every { capability.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
            val step = invocation.args[3] as WorkflowStepDefinition
            if (step.id == "entry") {
                workflowRepository.reserveRootCase(scope, namespace, workflowId, "fresh-root")
                CapabilityExecution(CapabilityOutcome.AgentCompleted("entry", "architect", "PASS"))
            } else {
                CapabilityExecution(CapabilityOutcome.CodeRefused("build", "build", "VERIFICATION_MANIFEST_MISSING", "missing"))
            }
        }
        val resolver = mockk<io.whozoss.factory.workspace.RunWorktreeResolver>()
        every { resolver.resolveBinding(any(), any(), any(), any(), any(), any(), any()) } answers {
            assertThat(invocation.args[5]).isEqualTo("fresh-root")
            io.whozoss.factory.workspace.CodeWorkspaceBinding(repoRoot, "run-environment", workflowId)
        }
        val runner = SessionRunService(workflowRepository, evidenceRepository, interactionRepository, capability, sseHub,
            runWorktreeResolver = resolver)
        assertThat(runner.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        verify(exactly = 1) { resolver.resolveBinding(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing manifest refusal is durable and terminal replay does not inspect it again`() {
        val workflowId = "wf-missing-manifest-durable"
        startSession("missing-manifest-durable", workflowId, listOf(stepJson("build", "code", "build", emptyList())))
        // Existing shared integration profile explicitly disables workspace checks.
        assertThat(sessionRunService.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        val failure = evidenceRepository.list(scope, namespace, workflowId).last { it.outcome == WorkflowStatuses.FAILED }
        assertThat(failure.facts["code"]).isEqualTo("VERIFICATION_MANIFEST_MISSING")
        assertThat(failure.facts["executed"]).isEqualTo(false)
        val count = evidenceRepository.list(scope, namespace, workflowId).size
        writeManifest("build" to "exit 0")
        assertThat(sessionRunService.runSession(scope, namespace, workflowId, repoRoot).status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(evidenceRepository.list(scope, namespace, workflowId)).hasSize(count)
    }

    @Test
    fun `a linear code DAG completes every step`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-linear"
        startSession(
            "linear-dag",
            workflowId,
            listOf(
                stepJson("s1", "code", "check", emptyList()),
                stepJson("s2", "code", "check", listOf("s1")),
            ),
        )

        val result = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.COMPLETED)
        val evidence = evidenceRepository.list(scope, namespace, workflowId)
        assertThat(evidence.map { it.stepId }).contains("s1", "s2")
    }

    @Test
    fun `a failed branch blocks its dependents while the independent branch continues`() {
        writeScript("pass", "exit 0")
        writeScript("fail", "exit 3")
        writeManifest(
            "backend-check" to "./factory/verification/pass",
            "frontend-check" to "./factory/verification/fail",
        )
        val workflowId = "wf-branches"
        startSession(
            "branch-dag",
            workflowId,
            listOf(
                stepJson("backend", "code", "backend-check", emptyList()),
                stepJson("backend-verify", "code", "backend-check", listOf("backend")),
                stepJson("frontend", "code", "frontend-check", emptyList()),
                stepJson("frontend-verify", "code", "backend-check", listOf("frontend")),
            ),
        )

        val result = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "backend")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "backend-verify")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "frontend")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "frontend-verify")).isEqualTo(WorkflowStatuses.BLOCKED)
    }

    @Test
    fun `a human step suspends the session and resumes when answered`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-human"
        startSession(
            "human-dag",
            workflowId,
            listOf(
                stepJson("s1", "code", "check", emptyList()),
                stepJson("s2", "human", "reviewer", listOf("s1")),
                stepJson("s3", "code", "check", listOf("s2")),
            ),
        )

        val first = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(first.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(statusOf(workflowId, "s3")).isEqualTo(WorkflowStatuses.PENDING)

        val interaction = interactionRepository.list(scope, namespace, workflowId, openOnly = true).single()
        assertThat(interaction.payload["actions"]).isEqualTo(
            listOf(
                mapOf("id" to "approve", "label" to "Approuver"),
                mapOf("id" to "reject", "label" to "Rejeter"),
            ),
        )

        val replied = workflowService.replyInteraction(
            scope,
            namespace,
            workflowId,
            interaction.interactionId,
            interaction.revision,
            "approve",
            "looks good",
            "alice",
            repoRoot,
        )
        assertThat(replied.status).isEqualTo(200)
        assertThat(interactionRepository.find(scope, namespace, workflowId, interaction.interactionId)?.status)
            .isEqualTo("closed")

        // Auto human resumption: the downstream code step is executed by
        // `replyInteraction` itself, with no manual `runSession`/`/continue`.
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s3")).isEqualTo(WorkflowStatuses.COMPLETED)
        val state = sessionRunService.sessionState(scope, namespace, workflowId)!!
        assertThat(state.status).isEqualTo(WorkflowStatuses.COMPLETED)
    }

    @Test
    fun `a rejected human step fails and blocks its dependents`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-human-reject"
        startSession(
            "human-reject-dag",
            workflowId,
            listOf(
                stepJson("s1", "human", "reviewer", emptyList()),
                stepJson("s2", "code", "check", listOf("s1")),
            ),
        )

        sessionRunService.runSession(scope, namespace, workflowId, repoRoot)
        answerHuman(workflowId, "s1", "reject")
        val result = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.BLOCKED)
    }

    @Test
    fun `an agent step runs through a fake capability and records an attempt`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-agent"
        startSession(
            "agent-dag",
            workflowId,
            listOf(
                stepJson("s1", "agent", "architect", emptyList()),
                stepJson("s2", "code", "check", listOf("s1")),
            ),
        )
        val service = agentSessionService(AgentTurnResult.Completed("PASS", mapOf("caseStatus" to "IDLE")))

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.COMPLETED)
        val evidence = evidenceRepository.list(scope, namespace, workflowId)
        assertThat(evidence).anyMatch { it.kind == "agent-turn" && it.stepId == "s1" }
    }

    @Test
    fun `a failed agent turn fails the step and blocks its dependents`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-agent-fail"
        startSession(
            "agent-fail-dag",
            workflowId,
            listOf(
                stepJson("s1", "agent", "architect", emptyList()),
                stepJson("s2", "code", "check", listOf("s1")),
            ),
        )
        val service = agentSessionService(AgentTurnResult.Failed("AGENTOS_UNAVAILABLE", "down"))

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "s2")).isEqualTo(WorkflowStatuses.BLOCKED)
    }

    @Test
    fun `re-running a completed session is idempotent`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-idempotent"
        startSession(
            "idempotent-dag",
            workflowId,
            listOf(stepJson("s1", "code", "check", emptyList())),
        )

        sessionRunService.runSession(scope, namespace, workflowId, repoRoot)
        val evidenceAfterFirst = evidenceRepository.list(scope, namespace, workflowId).size
        val second = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(second.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(evidenceRepository.list(scope, namespace, workflowId)).hasSize(evidenceAfterFirst)
    }

    @Test
    fun `a start-time ticket reaches the agent brief and is persisted`() {
        val workflowId = "wf-ticket"
        startSession(
            "ticket-dag",
            workflowId,
            listOf(stepJson("s1", "agent", "architect", emptyList())),
            ticket = "JIRA-42",
        )
        val briefs = mutableListOf<String>()
        val service = SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            CapabilityExecutionService(
                CapabilityResolver(
                    object : AgentTurnCapability {
                        override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                            briefs += request.brief ?: ""
                            return AgentTurnResult.Completed("PASS")
                        }
                    },
                ),
                workflowRepository,
                evidenceRepository,
                interactionRepository,
                attemptRepository,
                durableAgentAttemptService = mockk<DurableAgentAttemptService>(relaxed = true),
                agentOsExecutionAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true),
                agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            ),
            sseHub,
        )

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(briefs.single()).contains("JIRA-42")
        val instance = workflowRepository.findInstance(scope, namespace, workflowId)!!
        assertThat(instance.instance["ticket"]).isEqualTo("JIRA-42")
        assertThat(instance.projection["ticket"]).isEqualTo("JIRA-42")
    }

    @Test
    fun `a run-time ticket is persisted on the instance`() {
        val workflowId = "wf-ticket-run"
        startSession(
            "ticket-run-dag",
            workflowId,
            listOf(stepJson("s1", "human", "reviewer", emptyList())),
        )

        val result = sessionRunService.runSession(scope, namespace, workflowId, repoRoot, ticket = "JIRA-99")

        assertThat(result.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        val instance = workflowRepository.findInstance(scope, namespace, workflowId)!!
        assertThat(instance.instance["ticket"]).isEqualTo("JIRA-99")
        assertThat(instance.projection["ticket"]).isEqualTo("JIRA-99")
    }

    // ----- auto oracles (Phase 2, sub-task 4) ----------------------------

    @Test
    fun `an applicable oracle runs automatically and records pass evidence`() {
        writeScript("pass", "exit 0")
        writeManifest("smoke" to "./factory/verification/pass")
        val workflowId = "wf-auto-oracle"
        startSession(
            "oracle-smoke",
            workflowId,
            listOf(stepJson("verify-code", "code", "smoke", emptyList())),
        )

        val result = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "verify-code")).isEqualTo(WorkflowStatuses.COMPLETED)
        val oracleEvidence = evidenceRepository.list(scope, namespace, workflowId).filter { it.kind == "oracle-result" }
        assertThat(oracleEvidence).hasSize(1)
        assertThat(oracleEvidence.single().outcome).isEqualTo("pass")
        assertThat(oracleEvidence.single().facts["oracleId"]).isEqualTo("smoke")
        assertThat(oracleEvidence.single().source?.get("kind")).isEqualTo("factory-oracle")
    }

    @Test
    fun `an oracle whose applicability does not match is not run`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-no-oracle"
        startSession(
            "unrelated-dag",
            workflowId,
            listOf(stepJson("s1", "code", "check", emptyList())),
        )

        val result = sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(evidenceRepository.list(scope, namespace, workflowId).filter { it.kind == "oracle-result" }).isEmpty()
    }

    @Test
    fun `a step with no matching oracles succeeds even when oracleExecutionService is absent`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-no-oracle-service"
        startSession(
            "unrelated-no-service-dag",
            workflowId,
            listOf(stepJson("s1", "code", "check", emptyList())),
        )
        val registry = mockk<OracleDefinitionRegistry>()
        every { registry.list() } returns emptyList()
        val runnerWithoutOracleService = SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            CapabilityExecutionService(
                CapabilityResolver(),
                workflowRepository,
                evidenceRepository,
                interactionRepository,
                attemptRepository,
                durableAgentAttemptService = mockk<DurableAgentAttemptService>(relaxed = true),
                agentOsExecutionAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true),
                agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            ),
            sseHub,
            oracleDefinitionRegistry = registry,
            oracleExecutionService = null,
        )

        val result = runnerWithoutOracleService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(statusOf(workflowId, "s1")).isEqualTo(WorkflowStatuses.COMPLETED)
    }

    @Test
    fun `a failing applicable oracle fails the step and blocks its dependents`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-oracle-gate"
        startSession(
            "oracle-gate-dag",
            workflowId,
            listOf(
                stepJson("verify", "code", "check", emptyList()),
                stepJson("after", "code", "check", listOf("verify")),
            ),
        )
        val definition = OracleDefinition(
            id = "gate-oracle",
            version = "1.0.0",
            domain = "factory",
            argv = listOf("true"),
            timeoutMs = 1000,
            applicable = OracleApplicableCondition(workflowTypes = listOf("oracle-gate-dag"), stepIds = listOf("verify")),
        )
        val service = oracleGatedSession(definition, OracleExecutionStatus.FAILED)

        val result = service.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(result.status).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "verify")).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(statusOf(workflowId, "after")).isEqualTo(WorkflowStatuses.BLOCKED)
    }

    /**
     * A sequencer wired to a mocked oracle catalogue/service, so the failure
     * gating path is exercised deterministically (the real service always
     * terminalizes an execution as `SUCCEEDED`).
     */
    private fun oracleGatedSession(definition: OracleDefinition, status: OracleExecutionStatus): SessionRunService {
        val registry = mockk<OracleDefinitionRegistry>()
        every { registry.list() } returns listOf(definition)
        val oracleService = mockk<OracleExecutionService>()
        every { oracleService.run(any(), any()) } returns OracleRunResult(
            workflowId = "wf-oracle-gate",
            stepId = "verify",
            oracleId = definition.id,
            executionId = "exec-gate",
            status = status,
            revision = 2,
            outcome = status.dbValue,
            evidenceId = null,
            artifactId = null,
            created = true,
            idempotent = false,
        )
        return SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            CapabilityExecutionService(
                CapabilityResolver(),
                workflowRepository,
                evidenceRepository,
                interactionRepository,
                attemptRepository,
                durableAgentAttemptService = mockk<DurableAgentAttemptService>(relaxed = true),
                agentOsExecutionAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true),
                agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            ),
            sseHub,
            registry,
            oracleService,
        )
    }
}
