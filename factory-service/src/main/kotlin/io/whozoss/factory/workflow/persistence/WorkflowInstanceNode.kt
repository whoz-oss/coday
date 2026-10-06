package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the `workflow_instances` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId)` encoded as a single
 * string. [revision] is a plain optimistic-locking counter: it is compared-and-
 * swapped by an explicit Cypher `WHERE revision = $expectedRevision`, never
 * managed by SDN `@Version` (a conditional write is required, not a blind
 * increment). The instance and projection JSON documents stay as raw text.
 */
@Node("WorkflowInstance")
data class WorkflowInstanceNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val revision: Int,
    val status: String,
    val creationCommandHash: String? = null,
    val instance: String = "{}",
    val projection: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): WorkflowInstanceRecord = WorkflowInstanceRecord(
        namespaceId = namespaceId,
        workflowId = workflowId,
        revision = revision,
        status = status,
        creationCommandHash = creationCommandHash,
        instance = objectMapper.readJsonMap(instance),
        projection = objectMapper.readJsonMap(projection),
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId"

        fun fromDomain(
            scope: TenantScope,
            record: WorkflowInstanceRecord,
            objectMapper: ObjectMapper,
        ): WorkflowInstanceNode =
            WorkflowInstanceNode(
                id = compositeId(scope.organizationId, scope.workstreamId, record.namespaceId, record.workflowId),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = record.namespaceId,
                workflowId = record.workflowId,
                revision = record.revision,
                status = record.status,
                creationCommandHash = record.creationCommandHash,
                instance = objectMapper.writeJson(record.instance),
                projection = objectMapper.writeJson(record.projection),
            )
    }
}
