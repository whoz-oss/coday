package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository

/**
 * Neo4j implementation of [WorkflowEvidenceRepository].
 *
 * Replaces the retired `JdbcWorkflowRepository`'s evidence surface. The
 * append-only log is one `:WorkflowEvidence` node per item;
 * an identical replay is detected by the `idempotencyKey` property and, failing
 * that, by the composite node id, so a replay never appends a second row.
 */
@Repository
@Primary
class Neo4jWorkflowEvidenceRepository(
    private val evidence: SpringDataNeo4jWorkflowEvidenceRepository,
    private val objectMapper: ObjectMapper,
) : WorkflowEvidenceRepository {

    override fun list(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String?,
    ): List<WorkflowEvidenceItem> {
        val nodes = if (stepId == null) {
            evidence.findAllByInstance(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
        } else {
            evidence.findAllByInstanceAndStep(scope.organizationId, scope.workstreamId, namespaceId, workflowId, stepId)
        }
        return nodes.map { it.toDomain(objectMapper) }
    }

    override fun append(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        item: WorkflowEvidenceItem,
    ): EvidenceAppendResult {
        if (item.idempotencyKey != null) {
            val existing = evidence.findByIdempotencyKey(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                item.idempotencyKey,
            )
            if (existing != null) {
                return if (existing.evidenceId == item.evidenceId) {
                    EvidenceAppendResult.Idempotent(existing.toDomain(objectMapper))
                } else {
                    EvidenceAppendResult.Collision(WorkflowErrorCodes.IDEMPOTENCY_KEY_COLLISION)
                }
            }
        }
        val id = WorkflowEvidenceNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            item.evidenceId,
        )
        val existingById = evidence.findById(id).orElse(null)
        if (existingById != null) {
            return EvidenceAppendResult.Idempotent(existingById.toDomain(objectMapper))
        }
        evidence.save(WorkflowEvidenceNode.fromDomain(scope, namespaceId, workflowId, item, objectMapper))
        return EvidenceAppendResult.Created(item)
    }
}
