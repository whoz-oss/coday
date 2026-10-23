package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

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

    /** The capability of an attempt when only its trusted namespace identity is available. */
    @Query(
        """
        MATCH (c:ResultCapability)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
          AND c.namespaceId = ${'$'}namespaceId
          AND c.attemptId = ${'$'}attemptId
          AND c.capabilityType = ${'$'}capabilityType
        RETURN c
        LIMIT 1
        """,
    )
    fun findByNamespaceAndAttempt(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        attemptId: String,
        capabilityType: String,
    ): ResultCapabilityNode?

    /**
     * Atomically rotates a capability only while its reservation is unconsumed,
     * its attempt is non-terminal and its token digest still matches the caller's
     * observed digest.
     */
    @Query(
        """
        MATCH (c:ResultCapability {id: ${'$'}id}),
              (r:AgentStepResult {id: ${'$'}resultId}),
              (a:AgentStepAttempt)
        WHERE r.resultStatus = 'collision_detected'
          AND r.organizationId = c.organizationId
          AND r.workstreamId = c.workstreamId
          AND r.namespaceId = c.namespaceId
          AND r.workflowId = c.workflowId
          AND r.stepId = c.stepId
          AND r.attemptId = c.attemptId
          AND a.organizationId = c.organizationId
          AND a.workstreamId = c.workstreamId
          AND a.namespaceId = c.namespaceId
          AND a.workflowId = c.workflowId
          AND a.stepId = c.stepId
          AND a.attemptId = c.attemptId
          AND NOT a.status IN ['completed', 'failed', 'interrupted', 'indeterminate', 'superseded']
          AND c.tokenHash = ${'$'}expectedTokenHash
        SET c.tokenHash = ${'$'}tokenHash,
            c.payload = ${'$'}payload,
            c.createdAt = ${'$'}createdAt
        RETURN count(c) AS updated
        """,
    )
    fun rotateIfRefreshable(
        id: String,
        resultId: String,
        expectedTokenHash: String,
        tokenHash: String,
        payload: String,
        createdAt: Instant,
    ): Long

    /**
     * Read-only startup-reconciliation sweep: every capability still backed by
     * an unredeemed reservation row (`collision_detected`), across all tenant
     * scopes. The join on the six identity fields keeps each capability fenced
     * to its own reservation.
     */
    @Query(
        """
        MATCH (c:ResultCapability), (r:AgentStepResult)
        WHERE c.organizationId = r.organizationId
          AND c.workstreamId = r.workstreamId
          AND c.namespaceId = r.namespaceId
          AND c.workflowId = r.workflowId
          AND c.stepId = r.stepId
          AND c.attemptId = r.attemptId
          AND r.resultStatus = 'collision_detected'
        RETURN c
        ORDER BY c.createdAt ASC
        """,
    )
    fun findUnredeemedReserved(): List<ResultCapabilityNode>
}
