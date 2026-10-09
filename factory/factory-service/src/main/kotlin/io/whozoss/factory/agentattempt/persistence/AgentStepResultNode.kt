package io.whozoss.factory.agentattempt.persistence

import io.whozoss.factory.agentattempt.domain.AgentStepResultRow
import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of one AGENT-STEP result row.
 *
 * Replaces the V6 `agent_step_results` PostgreSQL row. A submission capability
 * is persisted together with a **reservation** row (status `collision_detected`,
 * payload `type = capability-reserved`); [Neo4jAgentStepResultRepository.submit]
 * then updates that same node in place with the authoritative submitted payload.
 * The former composite foreign key onto `result_capabilities` is materialised as
 * a plain `resultId` property — in the graph there is no referential constraint
 * to honour.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, stepId, attemptId,
 * resultId)` encoded as a single string.
 */
@Node("AgentStepResult")
data class AgentStepResultNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptId: String,
    val resultId: String,
    val resultStatus: String,
    val semanticSignature: String? = null,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(): AgentStepResultRow = AgentStepResultRow(
        namespaceId = namespaceId,
        workflowId = workflowId,
        stepId = stepId,
        attemptId = attemptId,
        resultId = resultId,
        resultStatus = resultStatus,
        semanticSignature = semanticSignature,
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
            resultId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId|$attemptId|$resultId"
    }
}
