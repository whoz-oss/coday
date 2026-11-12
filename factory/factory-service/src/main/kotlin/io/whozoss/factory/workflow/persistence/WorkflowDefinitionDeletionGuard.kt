package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.workflowException
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/** Definition write locks serialize deletion and durable pin writes in the caller's transaction. */
@Component
class WorkflowDefinitionDeletionGuard(
    private val client: Neo4jClient,
    private val mapper: ObjectMapper,
) {
    fun lock(organizationId: String, type: String, version: String): String? =
        client.query("""
            MATCH (d:WorkflowDefinition {id: ${'$'}id})
            SET d.pinLock = coalesce(d.pinLock, 0) + 1
            RETURN d.definitionHash AS hash
        """.trimIndent()).bind(WorkflowDefinitionNode.compositeId(organizationId, type, version)).to("id")
            .fetch().one().orElse(null)?.get("hash") as? String

    fun assertPinsAvailable(organizationId: String, versions: Map<String, String>, hashes: Map<String, String> = emptyMap()) {
        // Deterministic lock order for parent runs pinning multiple definitions.
        versions.toSortedMap().forEach { (type, version) ->
            val hash = lock(organizationId, type, version)
                ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND, "The pinned workflow definition '$type@$version' is unavailable.")
            if (hashes[type] != null && hash != hashes[type]) {
                throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH, "The pinned workflow definition hash does not match.")
            }
        }
    }

    fun assertUnused(organizationId: String, type: String, version: String) {
        // Legacy pins live in JSON; parse exact identities rather than substring matching. No lifecycle/status filter:
        // archived and purged projections, orphan instances and terminal parent-run catalogs remain protected.
        val rows = client.query("""
            MATCH (n) WHERE n.organizationId = ${'$'}organizationId
              AND (n:WorkflowInstance OR n:WorkflowProjection OR n:FactoryRun)
            RETURN n.instance AS instance, n.projection AS projection, n.definitionVersion AS version,
                   n.flowVersions AS versions
        """.trimIndent()).bind(organizationId).to("organizationId").fetch().all()
        val used = rows.any { row ->
            fun json(key: String): Map<*, *> = (row[key] as? String)?.let { mapper.readValue(it, Map::class.java) } ?: emptyMap<Any, Any>()
            val instance = json("instance")
            val projection = json("projection")
            json("versions")[type] == version ||
                (instance["workflowType"] == type && instance["definitionVersion"] == version) ||
                (projection["workflowType"] == type && (row["version"] ?: instance["definitionVersion"]) == version)
        }
        if (used) throw workflowException(
            "WORKFLOW_DEFINITION_IN_USE",
            "Cannot delete '$type@$version': a run pins this definition. Active and historical runs, including archived or purged runs, keep their definitions.",
        )
    }
}
