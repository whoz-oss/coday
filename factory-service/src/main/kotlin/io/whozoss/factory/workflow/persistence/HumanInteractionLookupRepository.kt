package io.whozoss.factory.workflow.persistence

import io.whozoss.factory.persistence.TenantScope
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository

/** Namespace-independent lookup used to resolve the authoritative interaction before status validation. */
@Repository
class HumanInteractionLookupRepository(
    private val neo4jClient: Neo4jClient,
) {
    fun findIdByWorkflowAndInteractionId(
        scope: TenantScope,
        workflowId: String,
        interactionId: String,
    ): String? =
        neo4jClient
            .query(
                """
                MATCH (h:HumanInteraction)
                WHERE h.organizationId = ${'$'}organizationId
                  AND h.workstreamId = ${'$'}workstreamId
                  AND h.workflowId = ${'$'}workflowId
                  AND h.interactionId = ${'$'}interactionId
                RETURN h.id AS id
                LIMIT 1
                """.trimIndent(),
            )
            .bindAll(
                mapOf(
                    "organizationId" to scope.organizationId,
                    "workstreamId" to scope.workstreamId,
                    "workflowId" to workflowId,
                    "interactionId" to interactionId,
                ),
            )
            .fetchAs(String::class.java)
            .mappedBy { _, record -> record["id"].asString() }
            .one()
            .orElse(null)
}
