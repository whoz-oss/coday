package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the durable execution attempt root
 * (Lot C durable-execution).
 *
 * Sibling of [AgentStepAttemptNode] (the result-path aggregate, left untouched):
 * this node carries the bridge-supplied execution identity plus the claim/lease
 * lifecycle. The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId)`
 * encoded as a single string (see [compositeId]). Because idempotence is by
 * `attemptId`, the attempt id is part of the key: a re-registration for the
 * same `attemptId` resolves to the very same node.
 *
 * `status` is persisted as the [AgentAttemptStatus.dbValue] string; `revision`
 * is the optimistic-locking counter bumped atomically by every CAS statement.
 */
@Node("DurableAgentAttempt")
data class DurableAgentAttemptNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptId: String,
    val caseId: String,
    val agentName: String,
    val attemptNumber: Int,
    val status: String,
    val revision: Int,
    val capabilityToken: String? = null,
    val ownerToken: String? = null,
    val turnCorrelation: String? = null,
    val commandId: String? = null,
    /** Durable turn brief captured at registration (see [DurableAgentAttempt.brief]). */
    val brief: String? = null,
    /** Work environment the attempt ran against (see [DurableAgentAttempt.environmentRef]). */
    val environmentRef: String? = null,
    /** Environment revision captured at reservation (see [DurableAgentAttempt.expectedEnvironmentRevision]). */
    val expectedEnvironmentRevision: Int? = null,
    val failureCode: String? = null,
    val resultEvidenceId: String? = null,
    val lastObservedEventId: String? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val leaseExpiresAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = attemptId,
        caseId = caseId,
        namespaceId = namespaceId,
        workflowId = workflowId,
        stepId = stepId,
        attemptNumber = attemptNumber,
        agentName = agentName,
        capabilityToken = capabilityToken,
        ownerToken = ownerToken,
        turnCorrelation = turnCorrelation,
        commandId = commandId,
        brief = brief,
        environmentRef = environmentRef,
        expectedEnvironmentRevision = expectedEnvironmentRevision,
        status = AgentAttemptStatus.fromDbValue(status),
        failureCode = failureCode,
        resultEvidenceId = resultEvidenceId,
        lastObservedEventId = lastObservedEventId,
        revision = revision,
        createdAt = createdAt,
        startedAt = startedAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        leaseExpiresAt = leaseExpiresAt,
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            stepId: String,
            attemptId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId|$attemptId"

        fun fromDomain(scope: TenantScope, attempt: DurableAgentAttempt): DurableAgentAttemptNode =
            DurableAgentAttemptNode(
                id = compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    attempt.namespaceId,
                    attempt.workflowId,
                    attempt.stepId,
                    attempt.attemptId,
                ),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = attempt.namespaceId,
                workflowId = attempt.workflowId,
                stepId = attempt.stepId,
                attemptId = attempt.attemptId,
                caseId = attempt.caseId,
                agentName = attempt.agentName,
                attemptNumber = attempt.attemptNumber,
                status = attempt.status.dbValue,
                revision = attempt.revision,
                capabilityToken = attempt.capabilityToken,
                ownerToken = attempt.ownerToken,
                turnCorrelation = attempt.turnCorrelation,
                commandId = attempt.commandId,
                brief = attempt.brief,
                environmentRef = attempt.environmentRef,
                expectedEnvironmentRevision = attempt.expectedEnvironmentRevision,
                failureCode = attempt.failureCode,
                resultEvidenceId = attempt.resultEvidenceId,
                lastObservedEventId = attempt.lastObservedEventId,
                startedAt = attempt.startedAt,
                completedAt = attempt.completedAt,
                leaseExpiresAt = attempt.leaseExpiresAt,
                createdAt = attempt.createdAt,
                updatedAt = attempt.updatedAt,
            )
    }
}
