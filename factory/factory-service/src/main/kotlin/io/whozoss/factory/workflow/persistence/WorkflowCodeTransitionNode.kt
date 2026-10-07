package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of an append-only `workflow_code_transitions` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, codeTransitionId)`
 * encoded as a single string; the insert is idempotent by construction.
 */
@Node("WorkflowCodeTransition")
data class WorkflowCodeTransitionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val codeTransitionId: String,
    val stepId: String,
    val outcome: String,
    val exitCode: Int? = null,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): WorkflowCodeTransitionRecord = WorkflowCodeTransitionRecord(
        codeTransitionId = codeTransitionId,
        stepId = stepId,
        outcome = outcome,
        exitCode = exitCode,
        payload = objectMapper.readJsonMap(payload),
        createdAt = createdAt.toString(),
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            codeTransitionId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$codeTransitionId"

        fun fromDomain(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            record: WorkflowCodeTransitionRecord,
            objectMapper: ObjectMapper,
        ): WorkflowCodeTransitionNode = WorkflowCodeTransitionNode(
            id = compositeId(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                record.codeTransitionId,
            ),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            codeTransitionId = record.codeTransitionId,
            stepId = record.stepId,
            outcome = record.outcome,
            exitCode = record.exitCode,
            payload = objectMapper.writeJson(record.payload),
        )
    }
}
