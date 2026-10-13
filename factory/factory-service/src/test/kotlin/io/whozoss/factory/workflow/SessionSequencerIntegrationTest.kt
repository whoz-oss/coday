package io.whozoss.factory.workflow

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

    // ----- tests ---------------------------------------------------------

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
