package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.executionPolicyOf
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the `workflow_definitions` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workflowType, version)` encoded as a single string, so a
 * scope-less access is impossible by construction. The verbatim definition JSON
 * stays in [definition] (the former JSONB column).
 */
@Node("WorkflowDefinition")
data class WorkflowDefinitionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val workflowType: String,
    val version: String,
    val definitionHash: String,
    val definition: String = "{}",
    val schemaVersion: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): WorkflowDefinitionRecord {
        val definitionMap = objectMapper.readJsonMap(definition)
        return WorkflowDefinitionRecord(
            workflowType = workflowType,
            version = version,
            definitionHash = definitionHash,
            definition = definitionMap,
            schemaVersion = schemaVersion,
            executionPolicy = executionPolicyOf(definitionMap),
        )
    }

    companion object {
        fun compositeId(organizationId: String, workflowType: String, version: String): String =
            "$organizationId|$workflowType|$version"

        fun fromDomain(
            scope: TenantScope,
            record: WorkflowDefinitionRecord,
            objectMapper: ObjectMapper,
        ): WorkflowDefinitionNode = WorkflowDefinitionNode(
            id = compositeId(scope.organizationId, record.workflowType, record.version),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            workflowType = record.workflowType,
            version = record.version,
            definitionHash = record.definitionHash,
            definition = objectMapper.writeJson(record.definition),
            schemaVersion = record.schemaVersion,
        )
    }
}
