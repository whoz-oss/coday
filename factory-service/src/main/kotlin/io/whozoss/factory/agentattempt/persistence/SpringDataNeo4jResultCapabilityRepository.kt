package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [ResultCapabilityNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the attempt
 * lookup (capability re-issuance guard) and the indexed token-digest lookup that
 * replaces the former tenant-wide JSONB scan.
 */
interface SpringDataNeo4jResultCapabilityRepository : Neo4jRepository<ResultCapabilityNode, String> {

    /** The capability of one (`capabilityType`, attempt) pair, or `null`. */
    @Query(
        """
        MATCH (c:ResultCapability)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
          AND c.namespaceId = ${'$'}namespaceId
          AND c.workflowId = ${'$'}workflowId
          AND c.stepId = ${'$'}stepId
          AND c.attemptId = ${'$'}attemptId
          AND c.capabilityType = ${'$'}capabilityType
        RETURN c
        LIMIT 1
        """,
    )
    fun findByAttempt(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        capabilityType: String,
    ): ResultCapabilityNode?

    /**
     * The capability whose indexed token digest equals [tokenHash], scoped to the
     * tenant. The caller still compares the digest in constant time.
     */
    @Query(
        """
        MATCH (c:ResultCapability)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
          AND c.tokenHash = ${'$'}tokenHash
        RETURN c
        ORDER BY c.createdAt ASC
        LIMIT 1
        """,
    )
    fun findByTokenHash(
        organizationId: String,
        workstreamId: String,
        tokenHash: String,
    ): ResultCapabilityNode?
}
