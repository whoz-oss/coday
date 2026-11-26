package io.whozoss.factory.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AttemptContextEnvelope
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.persistence.IdempotencyRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.NeedsResearchRouter
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
import io.whozoss.factory.worktree.WorktreeExecutionOccupancyService
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus
import io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor

/**
 * Real-Neo4j proofs of the governed retry of a failed agent step (WZ-36045).
 *
 * The unit tests mock the router, so they cannot see the one defect that matters here: a refused retry must leave
 * the failed attempt exactly as it was, worktree included. The occupation is TRANSFERRED to the successor inside
 * the command's transaction (never released), so a refusal at any later point rolls the transfer back with the
 * rest. Only the outbound AgentOS transport is mocked.
 */
@Import(FailedStepRetryIntegrationTest.ProxyConfiguration::class)
class FailedStepRetryIntegrationTest : Neo4jDomainIntegrationTest() {

    @TestConfiguration
    class ProxyConfiguration {
        @Bean
        @Primary
        fun failedStepRetryProxy(): AgentOsProxyClient = mockk()
    }

    @Autowired private lateinit var restTemplate: TestRestTemplate
    @Autowired private lateinit var proxy: AgentOsProxyClient
    @Autowired private lateinit var workflowService: WorkflowService
    @Autowired private lateinit var workflowRepository: WorkflowRepository
    @Autowired private lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired private lateinit var interactionRepository: HumanInteractionRepository
    @Autowired private lateinit var attempts: DurableAgentAttemptService
    @Autowired private lateinit var occupations: Neo4jWorktreeExecutionOccupancyRepository
    @Autowired private lateinit var router: NeedsResearchRouter
    @Autowired private lateinit var stepResults: AgentStepResultService
    @Autowired private lateinit var idempotency: IdempotencyRepository
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var sseHub: WorkflowSseHub
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var workstreamRepository: Neo4jWorkstreamRepository

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
    private val workflow = "wf-retry-it"
    private val step = "A"
    private val environment = "environment-retry-it"
    private val rootCase = "root-retry-it"
    private val resource = "resource-retry-it"
    private val predecessorId = CapabilityExecutionService.stableAttemptId(workflow, step)
    private val successorId = CapabilityExecutionService.retryAttemptId(workflow, step, 2)
    private val predecessorOwner = WorktreeExecutionOccupancyService.agentOwnerId(namespace, workflow, step, predecessorId)
    private val successorOwner = WorktreeExecutionOccupancyService.agentOwnerId(namespace, workflow, step, successorId)

    @BeforeEach
    fun prepare() {
        clearMocks(proxy)
        every { proxy.resolveRepoRoot(namespace, TEST_USER_ID) } returns "/repo"
        every { proxy.getRunCost(any(), any()) } returns null
        workstreamRepository.create(
            scope,
            Workstream(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = namespace,
                name = "Retry workstream",
                status = WorkstreamStatus.ACTIVE,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private data class Seed(val instanceRevision: Int, val predecessor: DurableAgentAttempt)

    /**
     * A governed run with ONE agent step `A` that failed, its failed attempt #1 (frozen context, case family and,
     * optionally, the physical-worktree occupation it kept because no result was accepted) and the step state.
     */
    private fun seedFailedRun(runStatus: String = WorkflowStatuses.FAILED, withOccupancy: Boolean = true): Seed {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-retry",
            "version" to "1.0.0",
            "title" to "Retry",
            "steps" to listOf(
                linkedMapOf(
                    "id" to step, "name" to "Step A",
                    "responsibility" to linkedMapOf("kind" to "agent", "name" to "Writer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        val definitionHash = hashWorkflowDefinition(valid.definition)
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord("wf-retry", "1.0.0", definitionHash, valid.definition),
        )
        val steps = listOf(
            mapOf(
                "id" to step, "name" to "Step A", "status" to WorkflowStatuses.FAILED,
                "responsibility" to mapOf("kind" to "agent", "name" to "Writer"), "dependsOn" to emptyList<String>(),
            ),
        )
        val instance = workflowRepository.insertInstance(
            scope,
            WorkflowInstanceRecord(
                namespaceId = namespace, workflowId = workflow, revision = 3, status = "active",
                creationCommandHash = "retry-it",
                instance = mapOf(
                    "workflowType" to "wf-retry", "definitionVersion" to "1.0.0", "definitionHash" to definitionHash,
                    "status" to runStatus, "steps" to steps,
                ),
                projection = mapOf("status" to runStatus, "schemaVersion" to "2", "steps" to steps),
            ),
        )
        workflowRepository.upsertStepState(
            scope,
            WorkflowStepStateRecord(
                namespace, workflow, step, 1, WorkflowStatuses.FAILED,
                mapOf("startedAt" to "2026-01-01T00:00:00Z", "completedAt" to "2026-01-01T00:01:00Z"),
            ),
        )
        return Seed(instance.revision, seedFailedPredecessor(withOccupancy))
    }

    private fun seedFailedPredecessor(withOccupancy: Boolean): DurableAgentAttempt {
        val brief = "Use Factory attemptId '$predecessorId' and Idempotency-Key 'factory:$predecessorId:result'."
        var attempt = attempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = predecessorId, caseId = "case-retry-it", namespaceId = namespace, workflowId = workflow,
                stepId = step, attemptNumber = 1, agentName = "Writer", brief = brief,
                contextEnvelope = AttemptContextEnvelope(
                    organizationId = scope.organizationId, workstreamId = scope.workstreamId, namespaceId = namespace,
                    workflowId = workflow, stepId = step, attemptNumber = 1, agentName = "Writer",
                    briefHash = CanonicalJsonHash.sha256(brief),
                ).toJson(),
                environmentRef = environment, expectedEnvironmentRevision = 1,
                rootCaseId = rootCase, parentCaseId = rootCase,
            ),
        )
        attempts.claim(scope, namespace, workflow, step, predecessorId, "lease-owner", 60_000)
        attempts.transition(scope, namespace, workflow, step, predecessorId, "lease-owner", AgentAttemptStatus.STARTING)
        attempt = attempts.transition(scope, namespace, workflow, step, predecessorId, "lease-owner", AgentAttemptStatus.RUNNING)
        if (withOccupancy) {
            val handle = occupations.acquire(scope, resource, WorktreeExecutionOccupancyService.AGENT_OWNER_KIND, predecessorOwner)
            attempt = attempts.bindOccupancy(
                scope, attempt, handle.resourceIdentity, handle.ownerKind!!, handle.ownerId!!, handle.generation,
            )
        }
        return attempts.finalize(
            scope, namespace, workflow, step, predecessorId, "lease-owner", AgentAttemptStatus.FAILED,
            failureCode = "FACTORY_STEP_RESULT_FAILED",
        )
    }

    private fun jsonType() = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    private fun headers(key: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        if (key != null) set("Idempotency-Key", key)
    }

    private fun getActions(): Map<String, Any?> = data(
        restTemplate.exchange(
            "/api/factory/workflows/$workflow/actions?namespaceId=$namespace", HttpMethod.GET,
            HttpEntity<Void>(headers()), jsonType(),
        ).body,
    )

    private fun postRetry(revision: Int, context: String? = "Use the staging database.", key: String = "retry-key-1"): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "/api/factory/workflows/$workflow/steps/$step/failed-step-retries", HttpMethod.POST,
            HttpEntity(
                buildMap<String, Any?> {
                    put("namespaceId", namespace)
                    put("expectedRevision", revision)
                    if (context != null) put("additionalContext", context)
                },
                headers(key),
            ),
            jsonType(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?> = body?.get("data") as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? = (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    @Suppress("UNCHECKED_CAST")
    private fun retryActions(actions: Map<String, Any?>) =
        (actions["allowedActions"] as List<Map<String, Any?>>).filter { it["type"] == "retry" }

    private fun assertPredecessorStillOwnsTheWorktree() {
        val occupation = occupations.find(resource)!!
        assertThat(occupation.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
        assertThat(occupation.ownerId).isEqualTo(predecessorOwner)
        assertThat(occupation.generation).isEqualTo(1L)
        assertThat(attempts.findByWorkflow(scope, namespace, workflow).map { it.attemptId }).containsExactly(predecessorId)
        assertThat(attempts.find(scope, namespace, workflow, step, predecessorId)!!.status).isEqualTo(AgentAttemptStatus.FAILED)
        assertThat(evidenceRepository.list(scope, namespace, workflow).filter { it.kind == NeedsResearchRouter.FAILED_STEP_RETRY_EVIDENCE_KIND })
            .isEmpty()
    }

    // ------------------------------------------------------------------
    // Positive HTTP path
    // ------------------------------------------------------------------

    @Test
    fun `a failed agent step of a failed run is offered a retry and the command creates attempt N+1 that owns the worktree`() {
        val seed = seedFailedRun()

        val action = retryActions(getActions()).single()
        assertThat(action["stepId"]).isEqualTo(step)
        assertThat(action["expectedRevision"]).isEqualTo(seed.instanceRevision)

        val response = postRetry(action["expectedRevision"] as Int)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = data(response.body)
        assertThat(body["attemptId"]).isEqualTo(successorId)
        assertThat(body["previousAttemptId"]).isEqualTo(predecessorId)
        assertThat(body["attemptNumber"]).isEqualTo(2)
        assertThat(body["idempotent"]).isEqualTo(false)
        assertThat(body["queued"]).isEqualTo(true)

        val successor = attempts.find(scope, namespace, workflow, step, successorId)!!
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(successor.caseId).isEqualTo(seed.predecessor.caseId)
        assertThat(successor.brief).contains("Use the staging database.").contains(successorId).doesNotContain(predecessorId)
        assertThat(successor.capabilityToken).isNotBlank()
        // The failed attempt stays an immutable record of the earlier try.
        assertThat(attempts.find(scope, namespace, workflow, step, predecessorId)).isEqualTo(seed.predecessor)

        // The worktree was handed over, never released: one ACTIVE occupation, one generation later, owned by N+1.
        val occupation = occupations.find(resource)!!
        assertThat(occupation.status).isEqualTo(WorktreeExecutionOccupancyStatus.ACTIVE)
        assertThat(occupation.ownerId).isEqualTo(successorOwner)
        assertThat(occupation.generation).isEqualTo(2L)
        assertThat(successor.occupancyResourceIdentity).isEqualTo(resource)
        assertThat(successor.occupancyOwnerId).isEqualTo(successorOwner)
        assertThat(successor.occupancyGeneration).isEqualTo(2L)

        val audit = evidenceRepository.list(scope, namespace, workflow)
            .single { it.kind == NeedsResearchRouter.FAILED_STEP_RETRY_EVIDENCE_KIND }
        assertThat(audit.facts["successorAttemptId"]).isEqualTo(successorId)
        assertThat(audit.source?.get("actorId")).isEqualTo(TEST_USER_ID)

        val next = workflowRepository.findInstance(scope, namespace, workflow)!!
        assertThat(next.revision).isEqualTo(seed.instanceRevision + 1)
        assertThat(next.instance["status"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(workflowRepository.findStepStates(scope, namespace, workflow).single().status).isEqualTo(WorkflowStatuses.READY)

        // A replay answers the same successor and never creates an attempt N+2 or moves the worktree again.
        val replay = postRetry(seed.instanceRevision)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(data(replay.body)["attemptId"]).isEqualTo(successorId)
        assertThat(data(replay.body)["idempotent"]).isEqualTo(true)
        assertThat(attempts.findByWorkflow(scope, namespace, workflow).map { it.attemptId })
            .containsExactlyInAnyOrder(predecessorId, successorId)
        assertThat(occupations.find(resource)!!.generation).isEqualTo(2L)
    }

    @Test
    fun `a run that is still active offers no retry and refuses the command even with a failed agent step`() {
        val seed = seedFailedRun(runStatus = WorkflowStatuses.RUNNING)

        assertThat(retryActions(getActions())).isEmpty()

        val response = postRetry(seed.instanceRevision)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorCode(response.body)).isEqualTo("INTERACTION_STALE")
        assertPredecessorStillOwnsTheWorktree()
        assertThat(workflowRepository.findInstance(scope, namespace, workflow)!!.revision).isEqualTo(seed.instanceRevision)
    }

    // ------------------------------------------------------------------
    // Refusals leave the failed attempt and its worktree untouched
    // ------------------------------------------------------------------

    @Test
    fun `an unresolvable repository root refuses the retry and the worktree stays with attempt 1 with no successor or evidence`() {
        val seed = seedFailedRun()
        every { proxy.resolveRepoRoot(namespace, TEST_USER_ID) } returns null

        val response = postRetry(seed.instanceRevision)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_REQUEST")
        assertPredecessorStillOwnsTheWorktree()
        assertThat(workflowRepository.findInstance(scope, namespace, workflow)!!.revision).isEqualTo(seed.instanceRevision)
    }

    @Test
    fun `a refusal after the occupation transfer rolls the transfer, the successor and the audit back with the command`() {
        val seed = seedFailedRun()
        // The command's own failure point is the LAST write: queueing the durable run. Everything before it
        // (audit evidence, successor registration, fenced transfer, instance compare-and-set) has already run.
        val failingSubmissions = mockk<SessionRunSubmissionService>()
        every { failingSubmissions.submit(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("queue down")
        val transactional = ProxyFactory(
            WorkflowService(
                workflowRepository, evidenceRepository, interactionRepository, sseHub,
                agentOsProxyClient = proxy,
                durableAgentAttemptService = attempts,
                agentStepResultService = stepResults,
                sessionRunSubmissionService = failingSubmissions,
                needsResearchRouter = router,
                idempotencyRepository = idempotency,
                objectMapper = objectMapper,
            ),
        ).apply {
            isProxyTargetClass = true
            addAdvice(TransactionInterceptor(transactionManager, AnnotationTransactionAttributeSource()))
        }.proxy as WorkflowService

        assertThatThrownBy {
            transactional.retryFailedStep(scope, namespace, workflow, step, seed.instanceRevision, "more info", TEST_USER_ID, "rollback-key")
        }.hasMessageContaining("queue down")

        // The old behaviour released the worktree in its own committed transaction: it would be RELEASED here.
        assertPredecessorStillOwnsTheWorktree()
        assertThat(attempts.find(scope, namespace, workflow, step, successorId)).isNull()
        val unchanged = workflowRepository.findInstance(scope, namespace, workflow)!!
        assertThat(unchanged.revision).isEqualTo(seed.instanceRevision)
        assertThat(unchanged.instance["status"]).isEqualTo(WorkflowStatuses.FAILED)
        assertThat(workflowRepository.findStepStates(scope, namespace, workflow).single().status).isEqualTo(WorkflowStatuses.FAILED)
    }

    // ------------------------------------------------------------------
    // The hand-over itself
    // ------------------------------------------------------------------

    private fun successorOf(predecessor: DurableAgentAttempt) = DurableAgentAttempt(
        attemptId = successorId, caseId = predecessor.caseId, namespaceId = namespace, workflowId = workflow, stepId = step,
        attemptNumber = 2, agentName = predecessor.agentName, brief = predecessor.brief,
        environmentRef = predecessor.environmentRef, expectedEnvironmentRevision = predecessor.expectedEnvironmentRevision,
        rootCaseId = predecessor.rootCaseId, parentCaseId = predecessor.parentCaseId,
    )

    @Test
    fun `a predecessor whose occupation was already released registers its successor without any transfer`() {
        val predecessor = seedFailedRun().predecessor
        occupations.release(
            scope, resource, predecessor.occupancyOwnerKind!!, predecessor.occupancyOwnerId!!, predecessor.occupancyGeneration!!,
        )

        val registered = attempts.registerRetryTransferringOccupancy(scope, predecessor, successorOf(predecessor))

        assertThat(registered.attemptNumber).isEqualTo(2)
        assertThat(registered.occupancyResourceIdentity).isNull()
        val occupation = occupations.find(resource)!!
        assertThat(occupation.status).isEqualTo(WorktreeExecutionOccupancyStatus.RELEASED)
        assertThat(occupation.generation).isEqualTo(1L)
    }

    @Test
    fun `a predecessor without any occupancy handle registers its successor and touches no occupation`() {
        val predecessor = seedFailedRun(withOccupancy = false).predecessor

        val registered = attempts.registerRetryTransferringOccupancy(scope, predecessor, successorOf(predecessor))

        assertThat(registered.occupancyResourceIdentity).isNull()
        assertThat(occupations.find(resource)).isNull()
    }
}
