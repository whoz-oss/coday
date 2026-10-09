package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of an append-only `workflow_evidence` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, evidenceId)` encoded
 * as a single string. The `idempotencyKey` and `stepId` are denormalised onto
 * the node so the replay lookup and the step filter are indexed property matches
 * instead of JSONB scans. The `source` / `facts` documents stay as JSON text.
 */
@Node("WorkflowEvidence")
data class WorkflowEvidenceNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val evidenceId: String,
    val stepId: String? = null,
    val kind: String,
    val outcome: String? = null,
    val source: String? = null,
    val facts: String = "{}",
    val idempotencyKey: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): WorkflowEvidenceItem = WorkflowEvidenceItem(
        evidenceId = evidenceId,
        namespaceId = namespaceId,
        workflowId = workflowId,
        stepId = stepId,
        kind = kind,
        outcome = outcome,
        source = objectMapper.readJsonMapOrNull(source),
        facts = objectMapper.readJsonMap(facts),
        idempotencyKey = idempotencyKey,
        createdAt = createdAt.toString(),
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            evidenceId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$evidenceId"

        fun fromDomain(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            item: WorkflowEvidenceItem,
            objectMapper: ObjectMapper,
        ): WorkflowEvidenceNode = WorkflowEvidenceNode(
            id = compositeId(scope.organizationId, scope.workstreamId, namespaceId, workflowId, item.evidenceId),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            evidenceId = item.evidenceId,
            stepId = item.stepId,
            kind = item.kind,
            outcome = item.outcome,
            source = item.source?.let { objectMapper.writeJson(it) },
            facts = objectMapper.writeJson(item.facts),
            idempotencyKey = item.idempotencyKey,
        )
    }
}
