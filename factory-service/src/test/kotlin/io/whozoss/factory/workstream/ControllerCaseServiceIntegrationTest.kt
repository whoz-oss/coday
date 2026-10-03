package io.whozoss.factory.workstream

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.UnprocessableEntityException
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.persistence.OracleExecutionRepository
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.web.FactoryHttpException
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workstream.domain.ControllerCaseBounds
import io.whozoss.factory.workstream.domain.ControllerCaseExecution
import io.whozoss.factory.workstream.domain.ControllerCaseStatus
import io.whozoss.factory.workstream.persistence.Neo4jControllerCaseRepository
import io.whozoss.factory.workstream.projection.ControllerResumptionPackageBuilder
import io.whozoss.factory.workstream.projection.WorkstreamProjectionService
import io.whozoss.factory.workstream.web.CompactControllerCaseRequest
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import io.whozoss.factory.workstream.web.StartControllerCaseRequest
import io.whozoss.factory.workstream.web.UpdateWorkstreamRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Integration tests of the controller case lifecycle (Phase 9).
 *
 * Covers the `controllerAgentRef` binding, the first start, the explicit
 * compaction (archive + renew with the same agent/workstream identity), the
 * bounded resumption context package rebuilt from the Phase 5 projection
 * (never a raw conversation dump), the read-only / resiliency invariant over
 * the underlying aggregates, the tenant trust boundary, and the idempotent
 * repository start.
 */
class ControllerCaseServiceIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workstreamService: WorkstreamService

    @Autowired
    private lateinit var controllerCaseService: ControllerCaseService

    @Autowired
    private lateinit var controllerCaseRepository: Neo4jControllerCaseRepository

    @Autowired
    private lateinit var projectionService: WorkstreamProjectionService

    @Autowired
    private lateinit var packageBuilder: ControllerResumptionPackageBuilder

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

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val caller: FactoryCaller
        get() = FactoryCaller(scope, "controller-tester", "controller-tester", "controller-tester")

    private val namespace = "ns-controller"

    private fun createRegistryEntry(controllerAgentRef: String? = null) {
        workstreamService.create(
            scope,
            CreateWorkstreamRequest(
                slug = WORKSTREAM_ID,
                name = "Controller WS",
                status = "active",
                controllerAgentRef = controllerAgentRef,
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

    private fun registerAttempt(workflowId: String, attemptId: String) {
        durableAgentAttemptService.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = "case-$attemptId",
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "step-1",
                attemptNumber = 1,
                agentName = "agent-worker",
            ),
        )
    }

    private fun openHumanInteraction(workflowId: String, interactionId: String) {
        interactionRepository.insert(
            scope,
            HumanInteractionRecord(
                interactionId = interactionId,
                namespaceId = namespace,
                workflowId = workflowId,
                stepId = "step-2",
                interactionType = "approval",
                status = "waiting",
                revision = 1,
                payload = linkedMapOf("prompt" to "Approve the controller case?", "actions" to emptyList<String>()),
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
                createdBy = "controller-tester",
            ),
        )
    }

    private fun detailCode(error: Throwable): String? =
        ((error as FactoryException).details as? Map<*, *>)?.get("code") as? String

    @Test
    fun `starting a controller case requires a controllerAgentRef and binds it as active`() {
        createRegistryEntry()

        assertThatThrownBy {
            controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())
        }
            .isInstanceOf(UnprocessableEntityException::class.java)
            .satisfies({ error ->
                assertThat((error as UnprocessableEntityException).statusCode).isEqualTo(422)
                assertThat(detailCode(error)).isEqualTo("CONTROLLER_AGENT_REF_REQUIRED")
            })

        // Registering the controllerAgentRef (Phase 5 registry field) unlocks the start.
        workstreamService.update(scope, WORKSTREAM_ID, UpdateWorkstreamRequest(controllerAgentRef = "agent://controller"))

        val started = controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())

        assertThat(started.status).isEqualTo(ControllerCaseStatus.ACTIVE)
        assertThat(started.sequence).isEqualTo(1)
        assertThat(started.controllerAgentRef).isEqualTo("agent://controller")
        assertThat(started.workstreamId).isEqualTo(WORKSTREAM_ID)
        assertThat(started.caseId).isNotBlank()
        assertThat(started.archivedAt).isNull()

        val active = controllerCaseService.getActiveCase(caller, WORKSTREAM_ID)
        assertThat(active?.caseId).isEqualTo(started.caseId)
        assertThat(active?.controllerAgentRef).isEqualTo("agent://controller")
    }

    @Test
    fun `starting twice without compaction is a conflict`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")
        controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())

        assertThatThrownBy {
            controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())
        }
            .isInstanceOf(ConflictException::class.java)
            .satisfies({ error ->
                assertThat((error as ConflictException).statusCode).isEqualTo(409)
                assertThat(detailCode(error)).isEqualTo("CONTROLLER_CASE_ALREADY_ACTIVE")
            })
    }

    @Test
    fun `compaction archives the current case and starts a new one with the same agent identity`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")
        val first = controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())
        publishWorkflow("wf-compact", "wf-demo", listOf("running"))

        val renewed = controllerCaseService.compactControllerCase(
            caller,
            WORKSTREAM_ID,
            CompactControllerCaseRequest(compactionReason = "token budget"),
        )

        // New active case: next sequence, new caseId, same agent + workstream identity.
        assertThat(renewed.caseId).isNotEqualTo(first.caseId)
        assertThat(renewed.status).isEqualTo(ControllerCaseStatus.ACTIVE)
        assertThat(renewed.sequence).isEqualTo(2)
        assertThat(renewed.controllerAgentRef).isEqualTo(first.controllerAgentRef)
        assertThat(renewed.workstreamId).isEqualTo(first.workstreamId)
        assertThat(renewed.archivedAt).isNull()
        assertThat(controllerCaseService.getActiveCase(caller, WORKSTREAM_ID)?.caseId).isEqualTo(renewed.caseId)

        // History: the previous case is archived with the compaction reason, ordered by sequence.
        val history = controllerCaseService.listHistory(caller, WORKSTREAM_ID)
        assertThat(history).hasSize(2)
        val archived = history[0]
        assertThat(archived.caseId).isEqualTo(first.caseId)
        assertThat(archived.status).isEqualTo(ControllerCaseStatus.ARCHIVED)
        assertThat(archived.sequence).isEqualTo(1)
        assertThat(archived.archivedAt).isNotNull()
        assertThat(archived.compactionReason).isEqualTo("token budget")
        assertThat(history[1].caseId).isEqualTo(renewed.caseId)
        assertThat(history[1].status).isEqualTo(ControllerCaseStatus.ACTIVE)
    }

    @Test
    fun `compaction without an active case is rejected`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")

        assertThatThrownBy {
            controllerCaseService.compactControllerCase(caller, WORKSTREAM_ID, CompactControllerCaseRequest())
        }
            .isInstanceOf(ConflictException::class.java)
            .satisfies({ error ->
                assertThat((error as ConflictException).statusCode).isEqualTo(409)
                assertThat(detailCode(error)).isEqualTo("NO_ACTIVE_CONTROLLER_CASE")
            })
    }

    @Test
    fun `the resumption context package is bounded and built from the projection, never a raw dump`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")
        publishWorkflow("wf-ctx", "wf-demo", listOf("running", "waiting_human", "blocked"))
        registerAttempt("wf-ctx", "att-ctx")
        openHumanInteraction("wf-ctx", "int-ctx")
        recordFailedOracle("wf-ctx", "exec-ctx")
        provisionEnvironment("wf-ctx", "env-ctx")

        val projection = projectionService.getAggregatedProjection(caller, WORKSTREAM_ID, null, null)
        val pkg = controllerCaseService.getContextPackage(caller, WORKSTREAM_ID, null, null)

        // Provenance: the package revision is the projection ETag.
        assertThat(pkg.sourceRevision).isEqualTo(projection.workstreamRevision)
        assertThat(pkg.sourceRevision).matches("^[0-9a-f]{16}$")

        // Strict per-section bounds.
        assertThat(pkg.activeWorkflows.size).isLessThanOrEqualTo(ControllerCaseBounds.MAX_WORKFLOW_ITEMS)
        assertThat(pkg.openHumanInteractions.size).isLessThanOrEqualTo(ControllerCaseBounds.MAX_HUMAN_ACTIONS)
        assertThat(pkg.blockers.size).isLessThanOrEqualTo(ControllerCaseBounds.MAX_BLOCKERS)
        assertThat(pkg.recentChanges.size).isLessThanOrEqualTo(ControllerCaseBounds.MAX_RECENT_CHANGES)

        // Counts mirror the authoritative projection section counts.
        assertThat(pkg.counts.activeWorkflows).isEqualTo(projection.activeWorkflows.count)
        assertThat(pkg.counts.running).isEqualTo(projection.steps.running)
        assertThat(pkg.counts.waitingHuman).isEqualTo(projection.steps.waitingHuman)
        assertThat(pkg.counts.blocked).isEqualTo(projection.steps.blocked)
        assertThat(pkg.counts.attempts).isEqualTo(projection.attempts.count)
        assertThat(pkg.counts.humanActions).isEqualTo(projection.humanActions.count)
        assertThat(pkg.counts.failedOracles).isEqualTo(projection.failedOracles.count)
        assertThat(pkg.counts.environments).isEqualTo(projection.environments.count)

        // Blockers summarize the blocked step and the failed oracle.
        assertThat(pkg.blockers.map { it.kind }).containsExactlyInAnyOrder("step", "oracle")
        assertThat(pkg.openHumanInteractions.single().interactionId).isEqualTo("int-ctx")

        // The serialized package is within the hard byte cap, carries only the
        // expected keys, and never a raw conversation text (the seeded prompt
        // must NOT leak into the package).
        val json = packageBuilder.toBoundedJson(pkg)
        assertThat(json.toByteArray(StandardCharsets.UTF_8).size)
            .isLessThanOrEqualTo(ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES)
        assertThat(json).doesNotContain("Approve the controller case?")
        @Suppress("UNCHECKED_CAST")
        val keys = (objectMapper.readValue(json, Map::class.java) as Map<String, Any?>).keys
        assertThat(keys).containsExactlyInAnyOrder(
            "workstreamId",
            "sourceRevision",
            "counts",
            "activeWorkflows",
            "openHumanInteractions",
            "blockers",
            "recentChanges",
        )

        // Starting persists the package provenance on the case, bounded.
        val started = controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())
        assertThat(started.contextRevision).isEqualTo(pkg.sourceRevision)
        assertThat(started.contextSummary).isNotNull()
        assertThat(started.contextSummary!!.toByteArray(StandardCharsets.UTF_8).size)
            .isLessThanOrEqualTo(ControllerCaseBounds.MAX_CONTEXT_SUMMARY_BYTES)
    }

    @Test
    fun `the service never mutates the underlying aggregates`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")
        publishWorkflow("wf-readonly", "wf-demo", listOf("running"))
        registerAttempt("wf-readonly", "att-readonly")

        val attemptRevisionBefore = durableAgentAttemptService
            .findByWorkflow(scope, namespace, "wf-readonly")
            .associate { it.attemptId to it.revision }
        @Suppress("UNCHECKED_CAST")
        val workflowRevisionBefore = (
            workflowService.listProjections(scope, namespace, "active")["items"] as List<Map<String, Any?>>
            ).associate { it["workflowId"] to it["revision"] }

        controllerCaseService.getContextPackage(caller, WORKSTREAM_ID, null, null)
        controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())
        controllerCaseService.compactControllerCase(
            caller,
            WORKSTREAM_ID,
            CompactControllerCaseRequest(compactionReason = "rotate"),
        )

        // Resiliency policy: workflows/attempts progress under their own
        // policies; the controller case lifecycle holds no lease over them.
        val attemptRevisionAfter = durableAgentAttemptService
            .findByWorkflow(scope, namespace, "wf-readonly")
            .associate { it.attemptId to it.revision }
        assertThat(attemptRevisionAfter).isEqualTo(attemptRevisionBefore)
        @Suppress("UNCHECKED_CAST")
        val workflowRevisionAfter = (
            workflowService.listProjections(scope, namespace, "active")["items"] as List<Map<String, Any?>>
            ).associate { it["workflowId"] to it["revision"] }
        assertThat(workflowRevisionAfter).isEqualTo(workflowRevisionBefore)
    }

    @Test
    fun `operations are tenant and workstream boundary enforced`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")

        listOf<(FactoryCaller, String) -> Unit>(
            { c, ws -> controllerCaseService.getActiveCase(c, ws) },
            { c, ws -> controllerCaseService.startControllerCase(c, ws, StartControllerCaseRequest()) },
            { c, ws -> controllerCaseService.compactControllerCase(c, ws, CompactControllerCaseRequest()) },
        ).forEach { operation ->
            assertThatThrownBy { operation(caller, "ws-untrusted") }
                .isInstanceOf(FactoryHttpException::class.java)
                .satisfies({ error ->
                    assertThat((error as FactoryHttpException).errorCode).isEqualTo("WORKSTREAM_BOUNDARY_VIOLATION")
                    assertThat(error.statusCode).isEqualTo(403)
                })
        }

        // A foreign caller scope can never reach the trusted workstream either.
        val foreignCaller = FactoryCaller(TenantScope(ORGANIZATION_ID, "ws-foreign"), "a", "a", null)
        assertThatThrownBy { controllerCaseService.getActiveCase(foreignCaller, WORKSTREAM_ID) }
            .isInstanceOf(FactoryHttpException::class.java)
    }

    @Test
    fun `operations on an unknown workstream are a NOT_FOUND`() {
        assertThatThrownBy { controllerCaseService.getActiveCase(caller, WORKSTREAM_ID) }
            .isInstanceOf(ResourceNotFoundException::class.java)
            .satisfies({ error -> assertThat(detailCode(error)).isEqualTo("WORKSTREAM_NOT_FOUND") })
        assertThatThrownBy {
            controllerCaseService.startControllerCase(caller, WORKSTREAM_ID, StartControllerCaseRequest())
        }.isInstanceOf(ResourceNotFoundException::class.java)
        assertThatThrownBy {
            controllerCaseService.compactControllerCase(caller, WORKSTREAM_ID, CompactControllerCaseRequest())
        }.isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `starting the same caseId twice at the repository is idempotent`() {
        createRegistryEntry(controllerAgentRef = "agent://controller")
        val now = Instant.now()
        val execution = ControllerCaseExecution(
            organizationId = scope.organizationId,
            workstreamId = WORKSTREAM_ID,
            caseId = "case-idempotent",
            controllerAgentRef = "agent://controller",
            sequence = 1,
            startedAt = now,
        )

        val first = controllerCaseRepository.startFirst(scope, execution)
        val replay = controllerCaseRepository.startFirst(scope, execution)

        assertThat(replay.caseId).isEqualTo(first.caseId)
        assertThat(replay.sequence).isEqualTo(first.sequence)
        assertThat(controllerCaseRepository.listHistory(scope, WORKSTREAM_ID)).hasSize(1)
        assertThat(controllerCaseRepository.findActive(scope, WORKSTREAM_ID)?.caseId).isEqualTo("case-idempotent")
    }
}
