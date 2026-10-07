package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of a `workflow_step_states` row (V3).
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, stepId)` encoded as a
 * single string. [revision] is the optimistic-locking counter: the upsert
 * increments it, and the status CAS compares it, both in explicit Cypher. The
 * step payload JSON stays as raw text.
 */
@Node("WorkflowStepState")
data class WorkflowStepStateNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val revision: Int,
    val status: String,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): WorkflowStepStateRecord = WorkflowStepStateRecord(
        namespaceId = namespaceId,
        workflowId = workflowId,
        stepId = stepId,
        revision = revision,
        status = status,
        payload = objectMapper.readJsonMap(payload),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            stepId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$stepId"

        fun fromDomain(
            scope: TenantScope,
            record: WorkflowStepStateRecord,
            revision: Int,
            objectMapper: ObjectMapper,
            createdAt: Instant = Instant.now(),
            updatedAt: Instant = Instant.now(),
        ): WorkflowStepStateNode = WorkflowStepStateNode(
            id = compositeId(
                scope.organizationId,
                scope.workstreamId,
                record.namespaceId,
                record.workflowId,
                record.stepId,
            ),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = record.namespaceId,
            workflowId = record.workflowId,
            stepId = record.stepId,
            revision = revision,
            status = record.status,
            payload = objectMapper.writeJson(record.payload),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
