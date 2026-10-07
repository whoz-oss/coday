package io.whozoss.factory.workstream

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.persistence.OracleExecutionRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.web.FactoryHttpException
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import io.whozoss.factory.workstream.web.WorkstreamBounds
import io.whozoss.factory.workstream.web.WorkstreamProjectionResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration tests of the read-only aggregated workstream projection (Phase 5).
 *
 * Seeds live state through the public beans of the owning aggregates
 * (workflows, attempts, oracles, environments, human interactions) and asserts
 * the calculated counts, bounded summaries, ETag stability, strict bounds and
 * trust-boundary enforcement — without any mutation of the underlying state.
 */
class WorkstreamProjectionIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workstreamService: WorkstreamService

    @Autowired
    private lateinit var projectionService: WorkstreamProjectionService

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var durableAgentAttemptService: DurableAgentAttemptService

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var oracleExecutionRepository: OracleExecutionRepository

    @Autowired
    private lateinit var workEnvironmentRepository: WorkEnvironmentRepository

    private val caller: FactoryCaller
        get() = FactoryCaller(scope, "projection-tester", "projection-tester", "projection-tester")

    private val namespace = "ns-projection"

    private fun uniqueSlug(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun createRegistryEntry(
        slug: String = WORKSTREAM_ID,
        namespaceId: String? = null,
        allowedWorkflowTypes: List<String>? = null,
    ) {
        workstreamService.create(
            scope,
            CreateWorkstreamRequest(
                slug = slug,
                name = "Projection WS",
                status = "active",
                namespaceId = namespaceId,
                allowedWorkflowTypes = allowedWorkflowTypes,
            ),
        )
    }

    private fun publishWorkflow(workflowId: String, workflowType: String, stepStatuses: List<String>) {
        workflowService.publishProjection(
            scope,
            namespace,
            workflowId,
            linkedMapOf<String, Any?>(
                "schemaVersion" to "1",
                "workflowId" to workflowId,
                "workflowType" to workflowType,
                "title" to "Workflow $workflowId",
                "status" to "running",
                "steps" to stepStatuses.mapIndexed { index, status ->
                    linkedMapOf<String, Any?>(
                        "id" to "step-${index + 1}",
                        "name" to "Step ${index + 1}",
                        "status" to status,
                    )
                },
            ),
            0,
            null,
        )
    }

    private fun registerAttempt(workflowId: String, attemptId: String, stepId: String = "step-1", attemptNumber: Int = 1) {
        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = "case-$attemptId",
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = stepId,
                attemptNumber = attemptNumber,
                agentName = "agent-projection",
            ),
        )
    }

    private fun openHumanInteraction(workflowId: String, interactionId: String, stepId: String = "step-2") {
        interactionRepository.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = stepId,
                interactionType = "approval",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf("prompt" to "Approve?", "actions" to emptyList<String>()),
            ),
        )
    }

    private fun recordFailedOracle(workflowId: String, executionId: String) {
        oracleExecutionRepository.save(
            scope,
            OracleExecution(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = namespace,
                workflowId = workflowId,
                executionId = executionId,
                oracleId = "smoke",
            ),
        )
        oracleExecutionRepository.updateStatus(scope, namespace, workflowId, executionId, OracleExecutionStatus.FAILED, 1)
    }

    private fun provisionEnvironment(workflowId: String, environmentId: String) {
        workEnvironmentRepository.insert(
            scope,
            WorkEnvironment(
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                environmentId = environmentId,
                workUnitId = "wu-$environmentId",
                workflowId = workflowId,
                namespaceId = namespace,
                repoRoot = "/tmp/repo-$environmentId",
                integrationBranch = "main",
                branch = "feature-$environmentId",
                worktreePath = "/tmp/repo-$environmentId/wt",
                createdBy = "projection-tester",
            ),
        )
    }

    private fun projection(limit: Int? = null, namespaceId: String? = null): WorkstreamProjectionResponse =
        projectionService.getAggregatedProjection(caller, WORKSTREAM_ID, namespaceId, limit)

    @Test
    fun `aggregates live state from every aggregate without mutating it`() {
        createRegistryEntry()
        publishWorkflow("wf-one", "wf-demo", listOf("running", "waiting_human", "completed"))
        publishWorkflow("wf-two", "wf-demo", listOf("blocked"))
        registerAttempt("wf-one", "att-1")
        registerAttempt("wf-one", "att-2", attemptNumber = 2)
        openHumanInteraction("wf-one", "int-1")
        recordFailedOracle("wf-one", "exec-1")
        provisionEnvironment("wf-one", "env-1")

        val attemptRevisionBefore = durableAgentAttemptService
            .findByWorkflow(scope, namespace, "wf-one")
            .associate { it.attemptId to it.revision }
        @Suppress("UNCHECKED_CAST")
        val workflowRevisionBefore = (
            workflowService.listProjections(scope, namespace, "active")["items"] as List<Map<String, Any?>>
            ).associate { it["workflowId"] to it["revision"] }

        val projection = projection()

        assertThat(projection.workstreamId).isEqualTo(WORKSTREAM_ID)
        assertThat(projection.status).isEqualTo("active")
        assertThat(projection.boundaryViolations).isEqualTo(0)

        // Active workflows: full count + bounded summaries.
        assertThat(projection.activeWorkflows.count).isEqualTo(2)
        assertThat(projection.activeWorkflows.items.map { it.workflowId }).containsExactlyInAnyOrder("wf-one", "wf-two")
        assertThat(projection.activeWorkflows.truncated).isFalse()

        // Steps of interest: 1 running, 1 waiting_human, 1 blocked.
        assertThat(projection.steps.running).isEqualTo(1)
        assertThat(projection.steps.waitingHuman).isEqualTo(1)
        assertThat(projection.steps.blocked).isEqualTo(1)
        assertThat(projection.steps.items).hasSize(3)

        // Attempts via the runtime-independent projection.
        assertThat(projection.attempts.count).isEqualTo(2)
        assertThat(projection.attempts.items.map { it.attemptId }).containsExactlyInAnyOrder("att-1", "att-2")
        assertThat(projection.attempts.items.map { it.status }).containsOnly("pending")

        // Open human actions.
        assertThat(projection.humanActions.count).isEqualTo(1)
        assertThat(projection.humanActions.items.single().interactionId).isEqualTo("int-1")

        // Failed oracles.
        assertThat(projection.failedOracles.count).isEqualTo(1)
        assertThat(projection.failedOracles.items.single().executionId).isEqualTo("exec-1")
        assertThat(projection.failedOracles.items.single().oracleId).isEqualTo("smoke")

        // Environments with lifecycle breakdown.
        assertThat(projection.environments.count).isEqualTo(1)
        assertThat(projection.environments.byState).containsEntry("provisioning", 1)
        assertThat(projection.environments.items.single().environmentId).isEqualTo("env-1")

        // Recent changes: newest first, never empty.
        assertThat(projection.recentChanges.items).isNotEmpty
        val timestamps = projection.recentChanges.items.map { it.timestamp }
        assertThat(timestamps).isEqualTo(timestamps.sortedDescending())

        // Stable ETag: identical state -> identical revision, 16 hex chars.
        assertThat(projection.workstreamRevision).matches("^[0-9a-f]{16}$")
        assertThat(projection().workstreamRevision).isEqualTo(projection.workstreamRevision)

        // Read-only invariant: the underlying aggregate state is untouched.
        val attemptRevisionAfter = durableAgentAttemptService
            .findByWorkflow(scope, namespace, "wf-one")
            .associate { it.attemptId to it.revision }
        assertThat(attemptRevisionAfter).isEqualTo(attemptRevisionBefore)
        @Suppress("UNCHECKED_CAST")
        val workflowRevisionAfter = (
            workflowService.listProjections(scope, namespace, "active")["items"] as List<Map<String, Any?>>
            ).associate { it["workflowId"] to it["revision"] }
        assertThat(workflowRevisionAfter).isEqualTo(workflowRevisionBefore)
    }

    @Test
    fun `the revision changes when the aggregated state changes`() {
        createRegistryEntry()
        publishWorkflow("wf-rev", "wf-demo", listOf("running"))
        val before = projection().workstreamRevision

        registerAttempt("wf-rev", "att-rev")

        assertThat(projection().workstreamRevision).isNotEqualTo(before)
    }

    @Test
    fun `declared workflow types bound the aggregation and count boundary violations`() {
        createRegistryEntry(allowedWorkflowTypes = listOf("wf-allowed"))
        publishWorkflow("wf-in", "wf-allowed", listOf("running"))
        publishWorkflow("wf-out", "wf-other", listOf("running"))

        val projection = projection()

        assertThat(projection.activeWorkflows.count).isEqualTo(1)
        assertThat(projection.activeWorkflows.items.single().workflowId).isEqualTo("wf-in")
        assertThat(projection.boundaryViolations).isEqualTo(1)
        assertThat(projection.steps.running).isEqualTo(1)
    }

    @Test
    fun `a namespace outside the declared workstream namespace is a boundary violation`() {
        createRegistryEntry(namespaceId = "ns-declared")

        assertThatThrownBy { projection(namespaceId = "ns-other") }
            .isInstanceOf(FactoryHttpException::class.java)
            .satisfies({ error ->
                assertThat((error as FactoryHttpException).errorCode).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")
                assertThat(error.statusCode).isEqualTo(403)
            })

        // The declared namespace itself is accepted.
        assertThat(projection(namespaceId = "ns-declared").namespaceId).isEqualTo("ns-declared")
    }

    @Test
    fun `a path workstream outside the trusted tenant scope is a boundary violation`() {
        createRegistryEntry()

        assertThatThrownBy {
            projectionService.getAggregatedProjection(caller, "ws-untrusted", null, null)
        }
            .isInstanceOf(FactoryHttpException::class.java)
            .satisfies({ error ->
                assertThat((error as FactoryHttpException).errorCode).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")
                assertThat(error.statusCode).isEqualTo(403)
            })

        // The trusted scope is always used, never one rebuilt from untrusted input.
        val foreignCaller = FactoryCaller(TenantScope(ORGANIZATION_ID, "ws-foreign"), "a", "a", null)
        assertThatThrownBy {
            projectionService.getAggregatedProjection(foreignCaller, WORKSTREAM_ID, null, null)
        }.isInstanceOf(FactoryHttpException::class.java)
    }

    @Test
    fun `an absent workstream registry entry is a NOT_FOUND`() {
        assertThatThrownBy { projection() }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `collection limits are coerced and items are bounded with truncation flags`() {
        createRegistryEntry()
        publishWorkflow("wf-cap", "wf-demo", listOf("running", "blocked"))
        registerAttempt("wf-cap", "att-cap-1")
        registerAttempt("wf-cap", "att-cap-2", attemptNumber = 2)

        // limit below the range coerces to 1 and flags truncation.
        val capped = projection(limit = 0)
        assertThat(capped.attempts.count).isEqualTo(2)
        assertThat(capped.attempts.items).hasSize(1)
        assertThat(capped.attempts.truncated).isTrue()
        assertThat(capped.activeWorkflows.items).hasSize(1)
        assertThat(capped.recentChanges.items).hasSize(1)

        // A limit above MAX_LIMIT is coerced down: seeding past the cap still
        // returns at most MAX_LIMIT items with the full count alongside.
        val heavySlug = uniqueSlug("att-heavy")
        repeat(WorkstreamBounds.MAX_LIMIT + 5) { index ->
            registerAttempt("wf-cap", "$heavySlug-$index", attemptNumber = index + 3)
        }
        val heavy = projection(limit = 500)
        assertThat(heavy.attempts.count).isEqualTo(WorkstreamBounds.MAX_LIMIT + 7)
        assertThat(heavy.attempts.items).hasSize(WorkstreamBounds.MAX_LIMIT)
        assertThat(heavy.attempts.truncated).isTrue()
        assertThat(heavy.steps.items.size).isLessThanOrEqualTo(WorkstreamBounds.MAX_LIMIT)
        assertThat(heavy.recentChanges.items.size).isLessThanOrEqualTo(WorkstreamBounds.MAX_LIMIT)
    }
}
