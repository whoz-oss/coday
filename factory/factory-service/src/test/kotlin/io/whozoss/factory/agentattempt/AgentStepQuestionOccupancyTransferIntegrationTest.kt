package io.whozoss.factory.agentattempt

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.adapter.agentos.AgentOsExecutionVerdict
import io.whozoss.factory.adapter.agentos.AgentOsObservationResult
import io.whozoss.factory.adapter.agentos.TrustedCaseBinding
import io.whozoss.factory.adapter.agentos.CaseHandle
import io.whozoss.factory.agentattempt.domain.AgentStepResultSubmitted
import io.whozoss.factory.agentattempt.domain.AgentStepResultStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultClaim
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyHandle
import io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator
import io.whozoss.factory.worktree.LocalWorktreeResourceIdentityResolver
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStaleRelease
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant

/** Real Neo4j proofs of the fenced N -> N+1 answer-time occupancy hand-off. */
class AgentStepQuestionOccupancyTransferIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var attempts: DurableAgentAttemptService
    @Autowired lateinit var occupations: Neo4jWorktreeExecutionOccupancyRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var environments: WorkEnvironmentRepository

    private val namespace = "ns-answer-transfer"
    private val workflow = "wf-answer-transfer"
    private val step = "step-answer-transfer"
    private val predecessorId = "attempt-answer-transfer-1"
    private val environment = "environment-answer-transfer"
    private val now = Instant.parse("2026-11-04T14:00:00Z")

    @Test
    fun `answer succession transfers active occupancy to N plus 1 and fences the old handle`() {
        val predecessor = seedWaitingPredecessorWithOccupancy()
        val successor = successor(predecessor)

        val landed = attempts.supersedeAndRegisterSuccessor(scope, predecessor, successor, now.plusSeconds(1))
        val active = occupations.find(predecessor.occupancyResourceIdentity!!)!!

        assertThat(active.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
        assertThat(active.generation).isEqualTo(predecessor.occupancyGeneration!! + 1)
        assertThat(active.ownerId).isEqualTo(
            WorktreeExecutionOccupancyService.agentOwnerId(namespace, workflow, step, successor.attemptId),
        )
        assertThat(landed.occupancyResourceIdentity).isEqualTo(active.resourceIdentity)
        assertThat(landed.occupancyGeneration).isEqualTo(active.generation)
        assertThat(landed.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(attempts.find(scope, namespace, workflow, step, predecessorId)!!.status)
            .isEqualTo(AgentAttemptStatus.SUPERSEDED)

        val occupancyService = WorktreeExecutionOccupancyService(environments, occupations, transactionManager = transactionManager)
        assertThat(occupancyService.releaseOwned(
            WorktreeExecutionOccupancyHandle(
                environment, predecessor.occupancyResourceIdentity, scope,
                predecessor.occupancyOwnerKind!!, predecessor.occupancyOwnerId!!, predecessor.occupancyGeneration,
            ),
        )).isFalse()
        assertThat(occupations.find(predecessor.occupancyResourceIdentity)!!).isEqualTo(active)

        assertThatThrownBy {
            occupations.release(
                scope, predecessor.occupancyResourceIdentity,
                predecessor.occupancyOwnerKind, predecessor.occupancyOwnerId,
                predecessor.occupancyGeneration,
            )
        }.isInstanceOf(WorktreeExecutionOccupancyStaleRelease::class.java)
    }

    @Test
    fun `failure after transfer and bind rolls predecessor successor and occupancy back`() {
        val predecessor = seedWaitingPredecessorWithOccupancy()
        val successor = successor(predecessor)

        assertThatThrownBy {
            attempts.supersedeAndRegisterSuccessor(scope, predecessor, successor, now.plusSeconds(1)) {
                error("injected after transfer and bind")
            }
        }.hasMessageContaining("injected after transfer and bind")

        val retained = attempts.find(scope, namespace, workflow, step, predecessorId)!!
        assertThat(retained.status).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        assertThat(attempts.find(scope, namespace, workflow, step, successor.attemptId)).isNull()
        val active = occupations.find(predecessor.occupancyResourceIdentity!!)!!
        assertThat(active.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
        assertThat(active.ownerId).isEqualTo(predecessor.occupancyOwnerId)
        assertThat(active.generation).isEqualTo(predecessor.occupancyGeneration)
    }

    @Test
    fun `atomic acquire and bind rolls acquisition back when a claim fence changes the attempt`() {
        val path = Files.createTempDirectory("d7-acquire-bind")
        environments.insert(
            scope,
            WorkEnvironment(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                environmentId = environment,
                workUnitId = "unit-acquire-bind",
                workflowId = workflow,
                namespaceId = namespace,
                repoRoot = path.toString(),
                integrationBranch = "integration/d7",
                branch = "work/d7",
                worktreePath = path.toString(),
                lifecycleState = WorkEnvironmentState.READY,
                createdBy = "test",
                revision = 7,
            ),
        )
        var attempt = attempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-acquire-bind", caseId = "case-acquire-bind", namespaceId = namespace,
                workflowId = workflow, stepId = "step-acquire-bind", attemptNumber = 1, agentName = "Worker",
                environmentRef = environment, expectedEnvironmentRevision = 7,
            ),
            now,
        )
        val stale = attempt
        attempt = attempts.claim(scope, namespace, workflow, "step-acquire-bind", attempt.attemptId, "claim-owner", 60_000, now)
        val occupancyService = WorktreeExecutionOccupancyService(environments, occupations, transactionManager = transactionManager)

        assertThatThrownBy {
            occupancyService.acquireAndBindForAttemptEnvironment(scope, environment, 7, stale) { handle ->
                attempts.bindOccupancy(scope, stale, handle.resourceIdentity, handle.ownerKind, handle.ownerId, handle.generation, now)
            }
        }
        assertThat(attempts.find(scope, namespace, workflow, "step-acquire-bind", attempt.attemptId)!!.occupancyResourceIdentity)
            .isNull()
        assertThat(attempts.find(scope, namespace, workflow, "step-acquire-bind", attempt.attemptId)!!.ownerToken)
            .isEqualTo("claim-owner")
    }

    @Test
    fun `replaying the same successor is rejected without N plus 2 or a second generation change`() {
        val predecessor = seedWaitingPredecessorWithOccupancy()
        val successor = successor(predecessor)
        attempts.supersedeAndRegisterSuccessor(scope, predecessor, successor, now.plusSeconds(1))
        val generation = occupations.find(predecessor.occupancyResourceIdentity!!)!!.generation

        assertThatThrownBy {
            attempts.supersedeAndRegisterSuccessor(scope, predecessor, successor, now.plusSeconds(2))
        }
        assertThat(attempts.findByWorkflow(scope, namespace, workflow).map { it.attemptId })
            .containsExactlyInAnyOrder(predecessorId, successor.attemptId)
        assertThat(occupations.find(predecessor.occupancyResourceIdentity)!!.generation).isEqualTo(generation)
    }

    @Autowired lateinit var questionService: AgentStepQuestionService
    @Autowired lateinit var interactions: HumanInteractionRepository
    @Autowired lateinit var workflows: WorkflowRepository
    @Autowired lateinit var evidence: WorkflowEvidenceRepository
    @Autowired lateinit var stepAttempts: AgentStepAttemptRepository
    @TempDir lateinit var root: Path

    private fun seedInteraction(predecessor: DurableAgentAttempt) = interactions.insert(
        scope,
        HumanInteractionRecord(
            interactionId = "question-transfer", namespaceId = namespace, workflowId = workflow,
            stepId = step, interactionType = "agent_question", status = "waiting", revision = 1,
            payload = mapOf("attemptId" to predecessor.attemptId, "prompt" to "Continue?", "questionType" to "free_text"),
        ),
    )

    @Test
    fun `full answer rolls back interaction predecessor successor and occupancy on stale transfer`() {
        val predecessor = seedWaitingPredecessorWithOccupancy(physicalWorktree = true)
        val interaction = seedInteraction(predecessor)
        // Advance the real row while retaining the old exact handle on the attempt.
        occupations.release(scope, predecessor.occupancyResourceIdentity!!, predecessor.occupancyOwnerKind!!,
            predecessor.occupancyOwnerId!!, predecessor.occupancyGeneration!!)
        occupations.acquire(scope, predecessor.occupancyResourceIdentity, "agent", "another-owner")
        val before = occupations.find(predecessor.occupancyResourceIdentity)!!

        assertThatThrownBy {
            questionService.answer(scope, namespace, workflow, interaction.interactionId, interaction.revision, "Continue", "alice")
        }.isInstanceOf(WorktreeExecutionOccupancyStaleRelease::class.java)

        val retained = interactions.find(scope, namespace, workflow, interaction.interactionId)!!
        assertThat(retained.status).isEqualTo("waiting")
        assertThat(retained.revision).isEqualTo(interaction.revision)
        assertThat(retained.payload).isEqualTo(interaction.payload)
        assertThat(attempts.find(scope, namespace, workflow, step, predecessorId)!!.status).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
        assertThat(attempts.find(scope, namespace, workflow, step, CapabilityExecutionService.retryAttemptId(workflow, step, 2))).isNull()
        assertThat(occupations.find(predecessor.occupancyResourceIdentity)).isEqualTo(before)
    }

    @Test
    fun `full answer transfers occupancy and successor executes PASS without stale predecessor release`() {
        val predecessor = seedWaitingPredecessorWithOccupancy(physicalWorktree = true)
        val interaction = seedInteraction(predecessor)
        val answered = questionService.answer(scope, namespace, workflow, interaction.interactionId, interaction.revision, "Continue", "alice")
        val successor = attempts.find(scope, namespace, workflow, step, answered.successorAttemptId)!!
        val active = occupations.find(predecessor.occupancyResourceIdentity!!)!!
        assertThat(interactions.find(scope, namespace, workflow, interaction.interactionId)!!.status).isEqualTo("closed")
        assertThat(successor.occupancyGeneration).isEqualTo(2L)
        assertThat(active.generation).isEqualTo(2L)
        assertThat(active.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
        val occupancy = WorktreeExecutionOccupancyService(environments, occupations, transactionManager = transactionManager)
        assertThat(occupancy.releaseOwned(WorktreeExecutionOccupancyHandle(
            environment, predecessor.occupancyResourceIdentity, scope, predecessor.occupancyOwnerKind!!,
            predecessor.occupancyOwnerId!!, predecessor.occupancyGeneration!!,
        ))).isFalse()
        assertThat(occupations.find(predecessor.occupancyResourceIdentity)).isEqualTo(active)

        val adapter = mockk<AgentOsExecutionAdapter>(relaxed = true)
        every { adapter.createOrRecoverExecution(any(), any(), any()) } answers {
            val binding = firstArg<TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        every { adapter.observeTurnResult(any(), any(), any(), any()) } returns AgentOsObservationResult(
            AgentOsExecutionVerdict.Indeterminate("OBSERVATION_TIMEOUT"),
        )
        val results = mockk<AgentStepResultService>()
        every { results.acceptedResult(scope, namespace, workflow, step, successor.attemptId) } returns AgentStepResultSubmitted(
            type = "result-submitted", resultId = "successor-pass", attemptId = successor.attemptId,
            workflowId = workflow, stepId = step, namespaceId = namespace, caseId = successor.caseId,
            agentName = successor.agentName, briefHash = "brief", status = AgentStepResultStatus.PASS,
            summary = "done", artifacts = emptyList(), claims = AgentStepResultClaim(emptyList()),
            findings = emptyList(), submittedAt = "2026-01-01T00:00:00Z", resultHash = "successor-pass-hash",
        )
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        val capability = CapabilityExecutionService(
            resolver = CapabilityResolver(), workflowRepository = workflows,
            evidenceRepository = evidence, interactionRepository = interactions, attemptRepository = stepAttempts,
            durableAgentAttemptService = attempts, agentOsExecutionAdapter = adapter, agentStepResultService = results,
            transactionManager = transactionManager, workEnvironmentRepository = environments,
            agentOsWorkspaceIdentityValidator = AgentOsWorkspaceIdentityValidator(proxy),
            worktreeExecutionOccupancyService = occupancy,
        )
        val execution = capability.resolveAndRecord(scope, namespace, workflow,
            WorkflowStepDefinition(step, "Write",
                WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Worker"), emptyList()), root)
        assertThat(execution.attemptId).isEqualTo(successor.attemptId)
        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        val completed = attempts.find(scope, namespace, workflow, step, successor.attemptId)!!
        assertThat(completed.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(completed.occupancyGeneration).isEqualTo(2L)
        assertThat(occupations.find(predecessor.occupancyResourceIdentity)!!.status).isEqualTo(WorktreeExecutionOccupancyStatus.RELEASED)
        assertThat(occupations.find(predecessor.occupancyResourceIdentity)!!.generation).isEqualTo(2L)
    }

    private fun seedWaitingPredecessorWithOccupancy(physicalWorktree: Boolean = false): DurableAgentAttempt {
        if (physicalWorktree) {
            environments.insert(scope, WorkEnvironment(
                organizationId = scope.organizationId, workstreamId = scope.workstreamId, environmentId = environment,
                workUnitId = "unit", workflowId = "parent-run", namespaceId = namespace, parentCaseId = "root-answer-transfer",
                repoRoot = root.toString(), integrationBranch = "main", branch = "work/test", worktreePath = root.toString(),
                lifecycleState = WorkEnvironmentState.READY, createdBy = "test", revision = 7,
            ))
            workflows.insertInstance(scope, WorkflowInstanceRecord(
                namespaceId = namespace, workflowId = workflow, revision = 1, status = "active", creationCommandHash = "answer-transfer",
                instance = mapOf("runId" to "parent-run"), projection = emptyMap(), rootCaseId = "root-answer-transfer",
            ))
        }
        var attempt = attempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = predecessorId,
                caseId = "case-answer-transfer",
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptNumber = 1,
                agentName = "Worker",
                brief = "brief",
                contextEnvelope = AttemptContextEnvelope(
                    organizationId = scope.organizationId,
                    workstreamId = scope.workstreamId,
                    namespaceId = namespace,
                    workflowId = workflow,
                    stepId = step,
                    attemptNumber = 1,
                    agentName = "Worker",
                    briefHash = CanonicalJsonHash.sha256("brief"),
                ).toJson(),
                environmentRef = environment,
                expectedEnvironmentRevision = 7,
                rootCaseId = "root-answer-transfer",
                parentCaseId = if (physicalWorktree) "root-answer-transfer" else "parent-answer-transfer",
            ),
            now,
        )
        attempt = attempts.claim(scope, namespace, workflow, step, predecessorId, "lease-owner", 60_000, now)
        attempt = attempts.transition(scope, namespace, workflow, step, predecessorId, "lease-owner", AgentAttemptStatus.STARTING, now = now)
        attempt = attempts.transition(scope, namespace, workflow, step, predecessorId, "lease-owner", AgentAttemptStatus.RUNNING, now = now)
        val ownerId = WorktreeExecutionOccupancyService.agentOwnerId(namespace, workflow, step, predecessorId)
        val identity = if (physicalWorktree) LocalWorktreeResourceIdentityResolver().resolve(root) else "resource-answer-transfer"
        val handle = occupations.acquire(scope, identity, WorktreeExecutionOccupancyService.AGENT_OWNER_KIND, ownerId)
        attempt = attempts.bindOccupancy(scope, attempt, handle.resourceIdentity, handle.ownerKind!!, handle.ownerId!!, handle.generation, now)
        return attempts.transition(
            scope, namespace, workflow, step, predecessorId, "lease-owner",
            AgentAttemptStatus.WAITING_HUMAN, now = now,
        )
    }

    private fun successor(predecessor: DurableAgentAttempt): DurableAgentAttempt {
        val id = CapabilityExecutionService.retryAttemptId(workflow, step, 2)
        return DurableAgentAttempt(
            attemptId = id,
            caseId = predecessor.caseId,
            namespaceId = namespace,
            workflowId = workflow,
            stepId = step,
            attemptNumber = 2,
            agentName = predecessor.agentName,
            brief = predecessor.brief,
            environmentRef = predecessor.environmentRef,
            expectedEnvironmentRevision = predecessor.expectedEnvironmentRevision,
            rootCaseId = predecessor.rootCaseId,
            parentCaseId = predecessor.parentCaseId,
        )
    }
}
