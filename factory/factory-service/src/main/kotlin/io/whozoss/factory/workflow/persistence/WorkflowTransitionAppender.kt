package io.whozoss.factory.workflow.persistence

import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import java.time.ZoneOffset

/** Atomic append boundary for the immutable workflow transition log. */
@Repository
class WorkflowTransitionAppender(
    private val neo4jClient: Neo4jClient,
) {
    fun appendIfAbsent(node: WorkflowTransitionNode) {
        val fromStepAssignment = if (node.fromStepId == null) "" else ", t.fromStepId = ${'$'}fromStepId"
        val query =
            """
            MERGE (t:WorkflowTransition {id: ${'$'}id})
            ON CREATE SET t.organizationId = ${'$'}organizationId,
                          t.workstreamId = ${'$'}workstreamId,
                          t.namespaceId = ${'$'}namespaceId,
                          t.workflowId = ${'$'}workflowId,
                          t.transitionId = ${'$'}transitionId,
                          t.toStepId = ${'$'}toStepId,
                          t.eventName = ${'$'}eventName,
                          t.payload = ${'$'}payload,
                          t.createdAt = ${'$'}createdAt$fromStepAssignment
            """.trimIndent()
        val parameters = mutableMapOf<String, Any>(
            "id" to node.id,
            "organizationId" to node.organizationId,
            "workstreamId" to node.workstreamId,
            "namespaceId" to node.namespaceId,
            "workflowId" to node.workflowId,
            "transitionId" to node.transitionId,
            "toStepId" to node.toStepId,
            "eventName" to node.eventName,
            "payload" to node.payload,
            // The Neo4j Java driver does not accept Instant as a raw query
            // parameter. ZonedDateTime is a native temporal value and SDN maps
            // it back to WorkflowTransitionNode.createdAt (Instant).
            "createdAt" to node.createdAt.atZone(ZoneOffset.UTC),
        ).apply {
            node.fromStepId?.let { put("fromStepId", it) }
        }
        neo4jClient.query(query).bindAll(parameters).run()
    }
}
