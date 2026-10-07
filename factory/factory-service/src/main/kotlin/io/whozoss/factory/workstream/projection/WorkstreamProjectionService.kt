package io.whozoss.factory.workstream.projection

import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.web.factoryError
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workstream.WorkstreamService
import io.whozoss.factory.workstream.web.AttemptSummary
import io.whozoss.factory.workstream.web.ChangeSummary
import io.whozoss.factory.workstream.web.EnvironmentSection
import io.whozoss.factory.workstream.web.EnvironmentSummary
import io.whozoss.factory.workstream.web.HumanActionSummary
import io.whozoss.factory.workstream.web.OracleFailureSummary
import io.whozoss.factory.workstream.web.StepCounts
import io.whozoss.factory.workstream.web.StepSummary
import io.whozoss.factory.workstream.web.WorkflowSummary
import io.whozoss.factory.workstream.web.WorkstreamBounds
import io.whozoss.factory.workstream.web.WorkstreamProjectionResponse
import io.whozoss.factory.workstream.web.WorkstreamSection
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Read-only aggregated projection of a workstream (Phase 5).
 *
 * Composes a bounded, coherent view from live state owned by the other
 * aggregates — workflows, durable agent attempts, oracle executions, work
 * environments and open human interactions — through their public injectable
 * beans only. It performs ZERO writes and duplicates ZERO state: every section
 * is calculated on demand from the authoritative stores, scoped by the caller's
 * trusted [io.whozoss.factory.persistence.TenantScope] (never by untrusted
 * input).
 *
 * Every collection is strictly bounded ([WorkstreamBounds]) and the response
 * carries a stable [WorkstreamProjectionResponse.workstreamRevision] (ETag)
 * derived from the registry revision and the aggregated state, so identical
 * states always produce identical revisions.
 */
@Service
class WorkstreamProjectionService(
    private val workstreamService: WorkstreamService,
    private val workflowService: WorkflowService,
    private val durableAgentAttemptService: DurableAgentAttemptService,
    private val workEnvironmentRepository: WorkEnvironmentRepository,
    private val neo4jClient: Neo4jClient,
) {

    private data class ObservedChange(val kind: String, val refId: String, val workflowId: String?, val at: Instant)

    /**
     * Calculate the aggregated projection of [workstreamId].
     *
     * @param namespaceId optional namespace filter; when the workstream
     *   declares a namespace, a mismatch is a boundary violation.
     * @param limit caller-supplied cap, coerced into [WorkstreamBounds].
     */
    fun getAggregatedProjection(
        caller: FactoryCaller,
        workstreamId: String,
        namespaceId: String?,
        limit: Int?,
    ): WorkstreamProjectionResponse {
        workstreamService.assertWithinWorkstream(caller, workstreamId)
        val scope = caller.scope
        val boundedLimit = WorkstreamBounds.boundedLimit(limit)
        val workstream = workstreamService.findDomain(scope, workstreamId)
            ?: throw ResourceNotFoundException(
                "Le workstream '$workstreamId' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )
        val namespaceFilter = namespaceId?.takeIf { it.isNotBlank() }
        if (namespaceFilter != null && workstream.namespaceId != null && workstream.namespaceId != namespaceFilter) {
            factoryError(
                403,
                "WORKSTREAM_BOUNDARY_VIOLATION",
                "The namespace '$namespaceFilter' is outside the declared workstream namespace.",
                mapOf("namespaceId" to namespaceFilter),
            )
        }

        val observed = ArrayList<ObservedChange>()
        observed += ObservedChange("workstream", workstream.workstreamId, null, workstream.updatedAt)

        // ---- Active workflows (bounded scan, declared-type boundary) --------
        @Suppress("UNCHECKED_CAST")
        val projectionItems = (
            workflowService.listProjections(scope, namespaceFilter, "active")["items"] as? List<Map<String, Any?>>
            ).orEmpty()
        val declaredTypes = workstream.allowedWorkflowTypes.toSet()
        val allowed = if (declaredTypes.isEmpty()) {
            projectionItems
        } else {
            projectionItems.filter { item -> workflowTypeOf(item)?.let { it in declaredTypes } ?: false }
        }
        val boundaryViolations = projectionItems.size - allowed.size
        val scanned = allowed.take(WorkstreamBounds.MAX_WORKFLOWS_SCANNED)

        val workflowSummaries = allowed.map { item ->
            val projection = item["projection"] as? Map<*, *>
            WorkflowSummary(
                workflowId = item["workflowId"] as? String ?: "",
                namespaceId = item["namespaceId"] as? String,
                workflowType = projection?.get("workflowType") as? String,
                title = projection?.get("title") as? String,
                status = projection?.get("status") as? String,
                revision = (item["revision"] as? Number)?.toInt() ?: 0,
            )
        }
        val activeWorkflows = WorkstreamSection(
            count = workflowSummaries.size,
            items = workflowSummaries.take(boundedLimit),
            truncated = workflowSummaries.size > boundedLimit,
        )

        // ---- Steps of interest (running / waiting_human / blocked) ----------
        val stepsOfInterest = ArrayList<StepSummary>()
        var running = 0
        var waitingHuman = 0
        var blocked = 0
        for (item in scanned) {
            val workflowId = item["workflowId"] as? String ?: continue
            val projection = item["projection"] as? Map<*, *> ?: continue
            val steps = projection["steps"] as? List<*> ?: continue
            for (raw in steps) {
                val step = raw as? Map<*, *> ?: continue
                when (step["status"] as? String) {
                    WorkflowStatuses.RUNNING -> running++
                    WorkflowStatuses.WAITING_HUMAN -> waitingHuman++
                    WorkflowStatuses.BLOCKED -> blocked++
                    else -> continue
                }
                stepsOfInterest += StepSummary(
                    workflowId = workflowId,
                    stepId = step["id"] as? String ?: "",
                    name = step["name"] as? String,
                    status = step["status"] as? String ?: "",
                )
            }
        }
        val stepTotal = running + waitingHuman + blocked
        val stepCounts = StepCounts(
            running = running,
            waitingHuman = waitingHuman,
            blocked = blocked,
            items = stepsOfInterest.take(boundedLimit),
            truncated = stepTotal > boundedLimit,
        )

        // ---- Attempts & open human actions (via the owning services) --------
        val attemptSummaries = ArrayList<AttemptSummary>()
        val humanActionSummaries = ArrayList<HumanActionSummary>()
        for (item in scanned) {
            val workflowId = item["workflowId"] as? String ?: continue
            val itemNamespace = item["namespaceId"] as? String ?: continue
            for (attempt in durableAgentAttemptService.findByWorkflow(scope, itemNamespace, workflowId)) {
                val dto = attempt.toDto()
                attemptSummaries += AttemptSummary(
                    attemptId = dto.attemptId,
                    workflowId = workflowId,
                    stepId = dto.stepId,
                    agentName = dto.agentName,
                    status = dto.status,
                    revision = dto.revision,
                    createdAt = dto.createdAt,
                    completedAt = dto.completedAt,
                )
                observed += ObservedChange("attempt", dto.attemptId, workflowId, dto.completedAt ?: dto.createdAt)
            }
            val interactions = workflowService.listInteractions(scope, itemNamespace, workflowId, true)
            @Suppress("UNCHECKED_CAST")
            val interactionItems = (interactions.data as? Map<String, Any?>)?.get("items") as? List<Map<String, Any?>>
            interactionItems.orEmpty().forEach { interaction ->
                humanActionSummaries += HumanActionSummary(
                    interactionId = interaction["interactionId"] as? String ?: "",
                    workflowId = workflowId,
                    stepId = interaction["stepId"] as? String,
                    interactionType = interaction["interactionType"] as? String,
                    status = interaction["status"] as? String,
                )
            }
        }
        val attempts = WorkstreamSection(
            count = attemptSummaries.size,
            items = attemptSummaries.take(boundedLimit),
            truncated = attemptSummaries.size > boundedLimit,
        )
        val humanActions = WorkstreamSection(
            count = humanActionSummaries.size,
            items = humanActionSummaries.take(boundedLimit),
            truncated = humanActionSummaries.size > boundedLimit,
        )

        // ---- Failed oracles (read-only scoped Cypher; writes nothing) -------
        val failedOracleCount = (
            neo4jClient
                .query(
                    """
                    MATCH (e:OracleExecution)
                    WHERE e.organizationId = ${'$'}org AND e.workstreamId = ${'$'}ws AND e.status = 'failed'
                    RETURN count(e) AS count
                    """.trimIndent(),
                )
                .bindAll(mapOf("org" to scope.organizationId, "ws" to scope.workstreamId))
                .fetchAs(Long::class.java)
                .one()
                .orElse(0L)
            ).toInt()
        val failedOracleRows = neo4jClient
            .query(
                """
                MATCH (e:OracleExecution)
                WHERE e.organizationId = ${'$'}org AND e.workstreamId = ${'$'}ws AND e.status = 'failed'
                RETURN e.workflowId AS workflowId, e.executionId AS executionId, e.oracleId AS oracleId,
                       e.namespaceId AS namespaceId, toString(e.updatedAt) AS updatedAt
                ORDER BY e.updatedAt DESC
                LIMIT ${'$'}maxRows
                """.trimIndent(),
            )
            .bindAll(
                mapOf(
                    "org" to scope.organizationId,
                    "ws" to scope.workstreamId,
                    "maxRows" to boundedLimit.toLong(),
                ),
            )
            .fetch()
            .all()
        val failedOracleSummaries = failedOracleRows.map { row ->
            val updatedAt = row["updatedAt"] as? String
            parseInstant(updatedAt)?.let { at ->
                observed += ObservedChange("oracle", row["executionId"] as? String ?: "", row["workflowId"] as? String, at)
            }
            OracleFailureSummary(
                executionId = row["executionId"] as? String ?: "",
                oracleId = row["oracleId"] as? String,
                workflowId = row["workflowId"] as? String,
                namespaceId = row["namespaceId"] as? String,
                updatedAt = updatedAt,
            )
        }
        val failedOracles = WorkstreamSection(
            count = failedOracleCount,
            items = failedOracleSummaries,
            truncated = failedOracleCount > failedOracleSummaries.size,
        )

        // ---- Environments ----------------------------------------------------
        val environmentList = workEnvironmentRepository.list(scope)
        val environmentSummaries = environmentList.map { environment ->
            observed += ObservedChange("environment", environment.environmentId, environment.workflowId, environment.createdAt)
            EnvironmentSummary(
                environmentId = environment.environmentId,
                workflowId = environment.workflowId,
                namespaceId = environment.namespaceId,
                lifecycleState = environment.lifecycleState.dbValue,
                revision = environment.revision,
                createdAt = environment.createdAt,
            )
        }
        val environments = EnvironmentSection(
            count = environmentSummaries.size,
            byState = environmentSummaries.groupingBy { it.lifecycleState }.eachCount(),
            items = environmentSummaries.take(boundedLimit),
            truncated = environmentSummaries.size > boundedLimit,
        )

        // ---- Recent changes (newest first, bounded) --------------------------
        val sortedChanges = observed.sortedByDescending { it.at }
        val recentChanges = WorkstreamSection(
            count = sortedChanges.size,
            items = sortedChanges.take(boundedLimit).map { change ->
                ChangeSummary(
                    kind = change.kind,
                    refId = change.refId,
                    workflowId = change.workflowId,
                    timestamp = change.at.toString(),
                )
            },
            truncated = sortedChanges.size > boundedLimit,
        )

        // ---- Stable workstream revision (ETag) -------------------------------
        val latestAt = sortedChanges.firstOrNull()?.at
        val workstreamRevision = WorkstreamRevision.compute(
            workstream,
            listOf(
                "activeWorkflows=${activeWorkflows.count}",
                "steps=${stepCounts.running},${stepCounts.waitingHuman},${stepCounts.blocked}",
                "attempts=${attempts.count}",
                "humanActions=${humanActions.count}",
                "failedOracles=${failedOracles.count}",
                "environments=${environments.count}",
                "boundaryViolations=$boundaryViolations",
                "latest=${latestAt?.toString() ?: ""}",
            ),
        )

        return WorkstreamProjectionResponse(
            workstreamId = workstream.workstreamId,
            namespaceId = namespaceFilter ?: workstream.namespaceId,
            status = workstream.status.dbValue,
            workstreamRevision = workstreamRevision,
            activeWorkflows = activeWorkflows,
            steps = stepCounts,
            attempts = attempts,
            humanActions = humanActions,
            failedOracles = failedOracles,
            environments = environments,
            recentChanges = recentChanges,
            boundaryViolations = boundaryViolations,
        )
    }

    private fun workflowTypeOf(item: Map<String, Any?>): String? =
        (item["projection"] as? Map<*, *>)?.get("workflowType") as? String

    private fun parseInstant(raw: String?): Instant? =
        raw?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        }
}
