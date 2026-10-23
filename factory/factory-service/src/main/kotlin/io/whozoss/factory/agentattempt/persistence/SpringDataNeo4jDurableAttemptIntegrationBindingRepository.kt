package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

interface SpringDataNeo4jDurableAttemptIntegrationBindingRepository :
    Neo4jRepository<DurableAttemptIntegrationBindingNode, String> {

    @Query(
        """
        MERGE (b:DurableAttemptIntegrationBinding {id: ${'$'}id})
        ON CREATE SET b.organizationId = ${'$'}organizationId,
                      b.workstreamId = ${'$'}workstreamId,
                      b.attemptId = ${'$'}attemptId,
                      b.serviceIdentityId = ${'$'}serviceIdentityId,
                      b.namespaceId = ${'$'}namespaceId,
                      b.workflowId = ${'$'}workflowId,
                      b.stepId = ${'$'}stepId,
                      b.briefHash = ${'$'}briefHash,
                      b.createdAt = ${'$'}createdAt
        RETURN b
        """,
    )
    fun bind(
        id: String,
        organizationId: String,
        workstreamId: String,
        attemptId: String,
        serviceIdentityId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        briefHash: String,
        createdAt: Instant,
    ): DurableAttemptIntegrationBindingNode
}
