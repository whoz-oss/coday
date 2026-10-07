package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of an append-only `workflow_transitions` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, transitionId)`
 * encoded as a single string; the insert is idempotent by construction (an
 * existing node is never rewritten). [createdAt] gives the log its ordering.
 */
@Node("WorkflowTransition")
data class WorkflowTransitionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val transitionId: String,
    val fromStepId: String? = null,
    val toStepId: String,
    val eventName: String,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            transitionId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$transitionId"

        fun fromDomain(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            transitionId: String,
            fromStepId: String?,
            toStepId: String,
            eventName: String,
            payload: Map<String, Any?>,
            objectMapper: ObjectMapper,
        ): WorkflowTransitionNode = WorkflowTransitionNode(
            id = compositeId(scope.organizationId, scope.workstreamId, namespaceId, workflowId, transitionId),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            transitionId = transitionId,
            fromStepId = fromStepId,
            toStepId = toStepId,
            eventName = eventName,
            payload = objectMapper.writeJson(payload),
        )
    }
}
