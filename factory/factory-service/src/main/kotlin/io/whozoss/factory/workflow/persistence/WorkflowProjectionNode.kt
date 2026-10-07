package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the `workflow_projections` row (V9).
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId)` encoded as a single
 * string. [revision] is the optimistic-locking counter and [lifecycleState] the
 * `active | removed | purged` lifecycle property; the JSON documents
 * (`projection`, `instance`, `relations`, `controllerExecution`) stay as raw
 * text, matching the former JSONB columns.
 */
@Node("WorkflowProjection")
data class WorkflowProjectionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val schemaVersion: String,
    val revision: Int,
    val projectionHash: String,
    val status: String,
    val projection: String = "{}",
    val instance: String? = null,
    val governanceMode: String? = null,
    val definitionVersion: String? = null,
    val definitionHash: String? = null,
    val relations: String? = null,
    val controllerExecution: String? = null,
    val lifecycleState: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): WorkflowProjectionRecord = WorkflowProjectionRecord(
        namespaceId = namespaceId,
        workflowId = workflowId,
        schemaVersion = schemaVersion,
        revision = revision,
        projectionHash = projectionHash,
        status = status,
        projection = objectMapper.readJsonMap(projection),
        instance = objectMapper.readJsonMapOrNull(instance),
        governanceMode = governanceMode,
        definitionVersion = definitionVersion,
        definitionHash = definitionHash,
        relations = objectMapper.readJsonMapOrNull(relations),
        controllerExecution = objectMapper.readJsonMapOrNull(controllerExecution),
        lifecycleState = lifecycleState,
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
            record: WorkflowProjectionRecord,
            revision: Int,
            lifecycleState: String,
            objectMapper: ObjectMapper,
            createdAt: Instant = Instant.now(),
            updatedAt: Instant = Instant.now(),
        ): WorkflowProjectionNode = WorkflowProjectionNode(
            id = compositeId(scope.organizationId, scope.workstreamId, record.namespaceId, record.workflowId),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = record.namespaceId,
            workflowId = record.workflowId,
            schemaVersion = record.schemaVersion,
            revision = revision,
            projectionHash = record.projectionHash,
            status = record.status,
            projection = objectMapper.writeJson(record.projection),
            instance = record.instance?.let { objectMapper.writeJson(it) },
            governanceMode = record.governanceMode,
            definitionVersion = record.definitionVersion,
            definitionHash = record.definitionHash,
            relations = record.relations?.let { objectMapper.writeJson(it) },
            controllerExecution = record.controllerExecution?.let { objectMapper.writeJson(it) },
            lifecycleState = lifecycleState,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
