package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the mutable AGENT-STEP attempt root.
 *
 * Replaces the V6 `agent_step_attempts` PostgreSQL row. The node [id] is the
 * composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId)`
 * encoded as a single string (see [compositeId]), so a scope-less access is
 * impossible by construction. The optimistic-locking [revision] is kept as a
 * plain property: [SpringDataNeo4jAgentStepAttemptRepository.terminalize]
 * increments it atomically in the same Cypher statement that flips the status,
 * mirroring the former `UPDATE ... SET revision = revision + 1`.
 *
 * `payload` stays as its raw JSON text (the former JSONB column).
 */
@Node("AgentStepAttempt")
data class AgentStepAttemptNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptId: String,
    val agentId: String,
    val status: String,
    val revision: Int,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): AgentStepAttemptRecord = AgentStepAttemptRecord(
        namespaceId = namespaceId,
        workflowId = workflowId,
        stepId = stepId,
        attemptId = attemptId,
        agentId = agentId,
        status = status,
        revision = revision,
        payload = payload,
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

        fun fromDomain(scope: TenantScope, attempt: AgentStepAttemptRecord): AgentStepAttemptNode =
            AgentStepAttemptNode(
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
                agentId = attempt.agentId,
                status = attempt.status,
                revision = attempt.revision,
                payload = attempt.payload,
            )
    }
}
