package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [AgentStepResultNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the
 * attempt-scoped row lookup that replaces the former
 * `SELECT ... WHERE ... ORDER BY created_at ASC LIMIT 1`.
 */
interface SpringDataNeo4jAgentStepResultRepository : Neo4jRepository<AgentStepResultNode, String> {

    /**
     * The oldest result row of an attempt, or `null` when the attempt has none.
     * A capability reserves exactly one row, so at most one result is returned.
     */
    @Query(
        """
        MATCH (r:AgentStepResult)
        WHERE r.organizationId = ${'$'}organizationId
          AND r.workstreamId = ${'$'}workstreamId
          AND r.namespaceId = ${'$'}namespaceId
          AND r.workflowId = ${'$'}workflowId
          AND r.stepId = ${'$'}stepId
          AND r.attemptId = ${'$'}attemptId
        RETURN r
        ORDER BY r.createdAt ASC
        LIMIT 1
        """,
    )
    fun findFirstByAttempt(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): AgentStepResultNode?

    /**
     * Rewrites a reserved result node in place with the authoritative submitted
     * payload, leaving `createdAt` untouched. Returns the number of nodes
     * updated (0 or 1).
     */
    @Query(
        """
        MATCH (r:AgentStepResult {id: ${'$'}id})
        SET r.resultStatus = ${'$'}resultStatus,
            r.semanticSignature = ${'$'}semanticSignature,
            r.payload = ${'$'}payload
        RETURN count(r) AS updated
        """,
    )
    fun updateResult(
        id: String,
        resultStatus: String,
        semanticSignature: String,
        payload: String,
    ): Long

    /** Number of result rows attached to an attempt (0, or 1 after a reservation). */
    @Query(
        """
        MATCH (r:AgentStepResult)
        WHERE r.organizationId = ${'$'}organizationId
          AND r.workstreamId = ${'$'}workstreamId
          AND r.namespaceId = ${'$'}namespaceId
          AND r.workflowId = ${'$'}workflowId
          AND r.stepId = ${'$'}stepId
          AND r.attemptId = ${'$'}attemptId
        RETURN count(r) AS count
        """,
    )
    fun countByAttempt(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): Long
}
