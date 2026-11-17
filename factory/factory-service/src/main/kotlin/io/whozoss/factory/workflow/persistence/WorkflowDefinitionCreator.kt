package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.OrganizationScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.ZoneOffset

/** Create-only registry boundary. MERGE plus the unique id constraint arbitrates concurrent editors. */
@Repository
class WorkflowDefinitionCreator(
    private val neo4jClient: Neo4jClient,
    private val objectMapper: ObjectMapper,
) {
    fun createIfAbsent(scope: OrganizationScope, record: WorkflowDefinitionRecord): Boolean {
        val node = WorkflowDefinitionNode.fromDomain(scope, record, objectMapper)
        val summary = neo4jClient.query(
            """
            MERGE (d:WorkflowDefinition {id: ${'$'}id})
            ON CREATE SET d.organizationId = ${'$'}organizationId,
                          d.workflowType = ${'$'}workflowType,
                          d.version = ${'$'}version,
                          d.definitionHash = ${'$'}definitionHash,
                          d.definition = ${'$'}definition,
                          d.schemaVersion = ${'$'}schemaVersion,
                          d.createdAt = ${'$'}createdAt,
                          d.updatedAt = ${'$'}createdAt
            """.trimIndent(),
        ).bindAll(
            mapOf(
                "id" to node.id,
                "organizationId" to node.organizationId,
                "workflowType" to node.workflowType,
                "version" to node.version,
                "definitionHash" to node.definitionHash,
                "definition" to node.definition,
                "schemaVersion" to node.schemaVersion,
                "createdAt" to node.createdAt.atZone(ZoneOffset.UTC),
            ),
        ).run()
        return summary.counters().nodesCreated() == 1
    }
}
