package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Backfill pre-pin-property records once, before startup completes. No JSON scans on deletion. */
@Component
class WorkflowDefinitionPinMigration(private val client: Neo4jClient, private val mapper: ObjectMapper) {
    @Transactional
    fun migrate() {
        // Labels are fixed application constants, never caller-controlled Cypher fragments.
        for (label in listOf("WorkflowInstance", "WorkflowProjection")) {
            val rows = client.query("MATCH (n:$label) WHERE n.pinnedDefinition IS NULL RETURN n.id AS id, n.instance AS instance, n.projection AS projection, n.definitionVersion AS version")
                .fetch().all()
            for (row in rows) {
                val instance = json(row["instance"])
                val projection = json(row["projection"])
                val type = instance["workflowType"] as? String ?: projection["workflowType"] as? String
                val version = instance["definitionVersion"] as? String ?: row["version"] as? String
                    ?: projection["definitionVersion"] as? String
                val pin = if (!type.isNullOrBlank() && !version.isNullOrBlank()) "$type@$version" else ""
                client.query("MATCH (n:$label {id: ${'$'}id}) WHERE n.pinnedDefinition IS NULL SET n.pinnedDefinition = ${'$'}pin")
                    .bindAll(mapOf("id" to row["id"], "pin" to pin)).run()
            }
        }
        val runs = client.query("MATCH (n:FactoryRun) WHERE n.pinnedDefinitions IS NULL RETURN n.id AS id, n.flowVersions AS versions").fetch().all()
        for (row in runs) {
            val pins = json(row["versions"]).entries.mapNotNull { (type, version) ->
                (version as? String)?.takeIf { type.isNotBlank() && it.isNotBlank() }?.let { "$type@$it" }
            }
            client.query("MATCH (n:FactoryRun {id: ${'$'}id}) WHERE n.pinnedDefinitions IS NULL SET n.pinnedDefinitions = ${'$'}pins")
                .bindAll(mapOf("id" to row["id"], "pins" to pins)).run()
        }
    }

    private fun json(value: Any?): Map<String, Any?> =
        (value as? String)?.let { raw -> mapper.readValue(raw, object : TypeReference<Map<String, Any?>>() {}) }.orEmpty()
}
