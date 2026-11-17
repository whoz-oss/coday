package io.whozoss.factory.workflow.persistence

import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.workflowException
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/** Serialize deletion with creation of durable pins, never with ordinary run/projection updates. */
@Component
class WorkflowDefinitionDeletionGuard(private val client: Neo4jClient) {
    fun lock(organizationId: String, type: String, version: String): String? =
        client.query("""
            MATCH (d:WorkflowDefinition {id: ${'$'}id})
            SET d.pinLock = coalesce(d.pinLock, 0) + 1
            RETURN d.definitionHash AS hash
        """.trimIndent()).bind(WorkflowDefinitionNode.compositeId(organizationId, type, version)).to("id")
            .fetch().one().orElse(null)?.get("hash") as? String

    fun assertPinsAvailable(organizationId: String, versions: Map<String, String>, hashes: Map<String, String> = emptyMap()) {
        versions.toSortedMap().forEach { (type, version) ->
            val hash = lock(organizationId, type, version)
                ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND, "The pinned workflow definition '$type@$version' is unavailable.")
            if (hashes[type] != null && hash != hashes[type]) {
                throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH, "The pinned workflow definition hash does not match.")
            }
        }
    }

    fun assertUnused(organizationId: String, type: String, version: String) {
        val parameters = mapOf("organizationId" to organizationId, "pinKey" to "$type@$version")
        val queries = listOf(
            "MATCH (n:WorkflowInstance {organizationId: ${'$'}organizationId, pinnedDefinition: ${'$'}pinKey}) RETURN count(n) > 0 AS used",
            "MATCH (n:WorkflowProjection {organizationId: ${'$'}organizationId, pinnedDefinition: ${'$'}pinKey}) RETURN count(n) > 0 AS used",
            "MATCH (n:FactoryRun {organizationId: ${'$'}organizationId}) WHERE ${'$'}pinKey IN coalesce(n.pinnedDefinitions, []) RETURN count(n) > 0 AS used",
        )
        if (queries.any { client.query(it).bindAll(parameters).fetch().one().orElse(null)?.get("used") == true }) {
            throw workflowException(
                "WORKFLOW_DEFINITION_IN_USE",
                "Cannot delete '$type@$version': a run pins this definition. Active and historical runs, including archived or purged runs, keep their definitions.",
            )
        }
    }
}
