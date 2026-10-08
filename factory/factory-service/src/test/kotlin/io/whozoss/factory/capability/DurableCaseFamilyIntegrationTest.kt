package io.whozoss.factory.capability

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.WorkflowService
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Integration tests of the Lot B durable case family (`rootCaseId` /
 * `parentCaseId`) of a plugin-driven execution run.
 *
 * The REAL Spring beans drive the orchestration (workflow repository, durable
 * attempt service, transaction manager) and a stateful fake
 * [AgentOsExecutionAdapter] stands in for AgentOS, so the whole bridge —
 * entry-step validation, atomic root case reservation, case family propagation
 * — runs exactly as in production.
 *
 * Acceptance criteria:
 *  - concurrent dispatch reserves exactly one root case;
 *  - a crash after reservation resumes with the SAME root case, never recreating it;
 *  - a subsequent step runs in a child sub-case linked to the root worktree;
 *  - a retry of a failed step keeps the worktree and creates a NEW sub-case;
 *  - two runs in the same namespace get distinct root cases (isolation);
 *  - legacy records without a root case stay `null` (strict compatibility);
 *  - a run with zero or several entry agent steps is refused before any remote call.
 */
class DurableCaseFamilyIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workflowService: WorkflowService

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
    private lateinit var transactionManager: PlatformTransactionManager

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-case-family"

    private fun isolatedWorkflowId(base: String): String = "$base-${UUID.randomUUID()}"

    private fun stepJson(id: String, kind: String, name: String, dependsOn: List<String>): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to "Step $id",
            "responsibility" to mapOf("kind" to kind, "name" to name),
            "dependsOn" to dependsOn,
        )

    private fun agentStep(id: String, dependsOn: List<String> = emptyList()): WorkflowStepDefinition =
        WorkflowStepDefinition(id, "Step $id", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "architect"), dependsOn)

    private fun startSession(workflowType: String, workflowId: String, steps: List<Map<String, Any?>>) {
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
        workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = workflowType, title = "Session $workflowType"),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
    }

    private fun service(adapter: AgentOsExecutionAdapter): CapabilityExecutionService =
        CapabilityExecutionService(
            CapabilityResolver(),
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            attemptRepository,
            durableAgentAttemptService = durableAgentAttemptService,
            agentOsExecutionAdapter = adapter,
            transactionManager = transactionManager,
        )

    private fun rootCaseOf(workflowId: String): String? =
        workflowRepository.findInstance(scope, namespace, workflowId)?.rootCaseId

    private fun attempt(workflowId: String, stepId: String, attemptId: String): DurableAgentAttempt? =
        durableAgentAttemptService.find(scope, namespace, workflowId, stepId, attemptId)

    /** Stateful fake of the AgentOS execution boundary: records turns and derives a verdict per case. */
    private class CaseFamilyAdapter(
        private val verdictFor: (String) -> AgentOsExecutionVerdict = { AgentOsExecutionVerdict.Succeeded(emptyMap()) },
    ) : AgentOsExecutionAdapter {
        val startTurns = CopyOnWriteArrayList<String>()
        private val knownCases = ConcurrentHashMap.newKeySet<String>()

        override fun createOrRecoverExecution(
            namespaceId: String,
            workflowId: String,
            stepId: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
            caseId: String,
        ): CaseHandle {
            val recovered = !knownCases.add(caseId)
            return CaseHandle(caseId = caseId, namespaceId = namespaceId, recovered = recovered)
        }

        override fun startTurn(
            caseId: String,
            persona: String,
            brief: String,
            externalUserId: String?,
            attemptId: String,
            capabilityToken: String?,
        ) {
            startTurns.add(caseId)
        }

        override fun observeTurn(caseId: String, attemptId: String, timeoutMs: Long): AgentOsExecutionVerdict =
            verdictFor(caseId)

        override fun reconcile(caseId: String): AgentOsExecutionVerdict = verdictFor(caseId)

        override fun interrupt(caseId: String, reason: String) = Unit

        override fun kill(caseId: String) = Unit
    }

    // ----- 1. Concurrent dispatch ⇒ exactly one root case -----------------

    @Test
    fun `concurrent dispatch reserves exactly one root case and one owning attempt`() {
        val workflowId = isolatedWorkflowId("wf-case-concurrent")
        startSession("case-concurrent", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))
        val adapter = CaseFamilyAdapter()
        val service = service(adapter)
        val step = agentStep("A")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<Any> {
                    gate.await()
                    runCatching { service.resolveAndRecord(scope, namespace, workflowId, step, repoRoot) }
                }
            }
            gate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val root = rootCaseOf(workflowId)
        assertThat(root).isEqualTo(CapabilityExecutionService.stableCaseId(workflowId, "A"))

        val attempt = attempt(workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"))!!
        assertThat(attempt.rootCaseId).isEqualTo(root)
        assertThat(attempt.parentCaseId).isNull()
        assertThat(attempt.caseId).isEqualTo(root)

        // A single AgentOS case id was ever used, and a single turn started.
        assertThat(adapter.startTurns).hasSize(1)
        assertThat(adapter.startTurns.toSet()).containsExactly(root)
    }

    // ----- 2. Crash after reservation ⇒ same root case, no recreation -----

    @Test
    fun `a crash after reservation resumes with the same root case without recreating it`() {
        val workflowId = isolatedWorkflowId("wf-case-recovery")
        startSession("case-recovery", workflowId, listOf(stepJson("A", "agent", "architect", emptyList())))

        // The reservation landed before the crash...
        assertThat(workflowRepository.reserveRootCase(scope, namespace, workflowId, "root-case-x"))
            .isEqualTo("root-case-x")
        // ... and a later, different candidate never overwrites / recreates it.
        assertThat(workflowRepository.reserveRootCase(scope, namespace, workflowId, "root-case-y"))
            .isEqualTo("root-case-x")
        assertThat(rootCaseOf(workflowId)).isEqualTo("root-case-x")

        // Resumption runs in the SAME reserved root case, without recreating it.
        val adapter = CaseFamilyAdapter()
        service(adapter).resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)

        assertThat(rootCaseOf(workflowId)).isEqualTo("root-case-x")
        val attempt = attempt(workflowId, "A", CapabilityExecutionService.stableAttemptId(workflowId, "A"))!!
        assertThat(attempt.caseId).isEqualTo("root-case-x")
        assertThat(attempt.rootCaseId).isEqualTo("root-case-x")
        assertThat(attempt.parentCaseId).isNull()
        assertThat(adapter.startTurns).containsExactly("root-case-x")
    }

    // ----- 3. Subsequent step ⇒ child sub-case of the root worktree -------

    @Test
    fun `a subsequent step runs in a child sub-case linked to the root worktree`() {
        val workflowId = isolatedWorkflowId("wf-case-child")
        startSession(
            "case-child",
            workflowId,
            listOf(
                stepJson("A", "agent", "architect", emptyList()),
                stepJson("B", "agent", "architect", listOf("A")),
            ),
        )
        val adapter = CaseFamilyAdapter()
        val service = service(adapter)
        service.resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)
        service.resolveAndRecord(scope, namespace, workflowId, agentStep("B", listOf("A")), repoRoot)

        val root = rootCaseOf(workflowId)!!
        assertThat(root).isEqualTo(CapabilityExecutionService.stableCaseId(workflowId, "A"))

        val b = attempt(workflowId, "B", CapabilityExecutionService.stableAttemptId(workflowId, "B"))!!
        assertThat(b.rootCaseId).isEqualTo(root)
        assertThat(b.parentCaseId).isEqualTo(root)
        assertThat(b.caseId).isEqualTo(CapabilityExecutionService.stableCaseId(workflowId, "B"))
        assertThat(b.caseId).isNotEqualTo(root)
    }

    // ----- 4. Retry of a failed step ⇒ new sub-case, same worktree --------

    @Test
    fun `a retry of a failed step keeps the worktree and creates a new sub-case`() {
        val workflowId = isolatedWorkflowId("wf-case-retry")
        startSession(
            "case-retry",
            workflowId,
            listOf(
                stepJson("A", "agent", "architect", emptyList()),
                stepJson("B", "agent", "architect", listOf("A")),
            ),
        )
        val failedCaseB = CapabilityExecutionService.stableCaseId(workflowId, "B")
        val adapter = CaseFamilyAdapter { caseId ->
            if (caseId == failedCaseB) {
                AgentOsExecutionVerdict.Failed(code = "AGENT_FAILED", message = "boom")
            } else {
                AgentOsExecutionVerdict.Succeeded(emptyMap())
            }
        }
        val service = service(adapter)
        service.resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)
        service.resolveAndRecord(scope, namespace, workflowId, agentStep("B", listOf("A")), repoRoot)

        val root = rootCaseOf(workflowId)!!
        val firstB = attempt(workflowId, "B", CapabilityExecutionService.stableAttemptId(workflowId, "B"))!!
        assertThat(firstB.status).isEqualTo(AgentAttemptStatus.FAILED)
        assertThat(firstB.caseId).isEqualTo(failedCaseB)
        assertThat(firstB.rootCaseId).isEqualTo(root)
        assertThat(firstB.parentCaseId).isEqualTo(root)

        // Retry: a brand-new attempt in the SAME worktree, with a NEW sub-case.
        val nextNumber = durableAgentAttemptService.nextAttemptNumber(scope, namespace, workflowId, "B")
        assertThat(nextNumber).isEqualTo(2)
        val retryAttemptId = CapabilityExecutionService.retryAttemptId(workflowId, "B", nextNumber)
        val retryCaseId = "subcase-${UUID.randomUUID()}"
        val retry = durableAgentAttemptService.registerRetry(
            scope,
            DurableAgentAttempt(
                attemptId = retryAttemptId,
                caseId = retryCaseId,
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "B",
                attemptNumber = nextNumber,
                agentName = "architect",
                rootCaseId = root,
                parentCaseId = root,
            ),
        )

        assertThat(retry.rootCaseId).isEqualTo(root)
        assertThat(retry.parentCaseId).isEqualTo(root)
        assertThat(retry.caseId).isEqualTo(retryCaseId)
        assertThat(retry.caseId).isNotEqualTo(failedCaseB)

        // The failed attempt is immutable: same case id, same status.
        val stillFirst = attempt(workflowId, "B", CapabilityExecutionService.stableAttemptId(workflowId, "B"))!!
        assertThat(stillFirst.caseId).isEqualTo(failedCaseB)
    }

    // ----- 5. Isolation ⇒ two runs, two worktrees -------------------------

    @Test
    fun `two runs in the same namespace get distinct root cases`() {
        val workflowId1 = isolatedWorkflowId("wf-case-iso-1")
        val workflowId2 = isolatedWorkflowId("wf-case-iso-2")
        startSession("case-iso-1", workflowId1, listOf(stepJson("A", "agent", "architect", emptyList())))
        startSession("case-iso-2", workflowId2, listOf(stepJson("A", "agent", "architect", emptyList())))
        val adapter = CaseFamilyAdapter()
        val service = service(adapter)

        service.resolveAndRecord(scope, namespace, workflowId1, agentStep("A"), repoRoot)
        service.resolveAndRecord(scope, namespace, workflowId2, agentStep("A"), repoRoot)

        val root1 = rootCaseOf(workflowId1)
        val root2 = rootCaseOf(workflowId2)
        assertThat(root1).isNotNull()
        assertThat(root2).isNotNull()
        assertThat(root1).isNotEqualTo(root2)
    }

    // ----- 6. Legacy data ⇒ null family, strict compatibility -------------

    @Test
    fun `legacy records without a root case stay null and are never converted`() {
        val workflowId = isolatedWorkflowId("wf-case-legacy")
        workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = workflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "legacy-$workflowId",
                instance = mapOf("workflowId" to workflowId),
                projection = emptyMap(),
            ),
        )
        assertThat(rootCaseOf(workflowId)).isNull()

        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = "legacy-attempt",
                caseId = "legacy-case",
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "A",
                attemptNumber = 1,
                agentName = "architect",
            ),
        )
        val legacy = attempt(workflowId, "A", "legacy-attempt")!!
        assertThat(legacy.rootCaseId).isNull()
        assertThat(legacy.parentCaseId).isNull()
    }

    // ----- 7. Refusal ⇒ zero or several entry agent steps -----------------

    @Test
    fun `execution is refused when there is no entry agent step`() {
        val workflowId = isolatedWorkflowId("wf-case-zero-entry")
        startSession(
            "case-zero-entry",
            workflowId,
            listOf(
                stepJson("pre", "code", "smoke", emptyList()),
                stepJson("A", "agent", "architect", listOf("pre")),
            ),
        )
        val adapter = CaseFamilyAdapter()

        val failure = assertThrows(IllegalStateException::class.java) {
            service(adapter).resolveAndRecord(scope, namespace, workflowId, agentStep("A", listOf("pre")), repoRoot)
        }

        assertThat(failure.message).contains("exactly 1 entry agent step")
        assertThat(adapter.startTurns).isEmpty()
        assertThat(rootCaseOf(workflowId)).isNull()
    }

    @Test
    fun `execution is refused when several entry agent steps exist`() {
        val workflowId = isolatedWorkflowId("wf-case-multi-entry")
        startSession(
            "case-multi-entry",
            workflowId,
            listOf(
                stepJson("A", "agent", "architect", emptyList()),
                stepJson("X", "agent", "architect", emptyList()),
            ),
        )
        val adapter = CaseFamilyAdapter()

        val failure = assertThrows(IllegalStateException::class.java) {
            service(adapter).resolveAndRecord(scope, namespace, workflowId, agentStep("A"), repoRoot)
        }

        assertThat(failure.message).contains("exactly 1 entry agent step")
        assertThat(adapter.startTurns).isEmpty()
        assertThat(rootCaseOf(workflowId)).isNull()
    }
}
