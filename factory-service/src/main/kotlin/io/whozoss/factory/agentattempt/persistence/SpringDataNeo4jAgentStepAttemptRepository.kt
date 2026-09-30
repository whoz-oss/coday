package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [AgentStepAttemptNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the graph-native
 * terminal transition that replaces the former
 * `UPDATE agent_step_attempts SET status = :status, revision = revision + 1`.
 */
interface SpringDataNeo4jAgentStepAttemptRepository : Neo4jRepository<AgentStepAttemptNode, String> {

    /**
     * Atomically transitions the attempt to [status], incrementing the
     * optimistic-locking `revision`. Returns the number of nodes updated
     * (0 or 1).
     */
    @Query(
        """
        MATCH (a:AgentStepAttempt {id: ${'$'}id})
        SET a.status = ${'$'}status,
            a.revision = a.revision + 1,
            a.updatedAt = ${'$'}updatedAt
        RETURN count(a) AS updated
        """,
    )
    fun terminalize(id: String, status: String, updatedAt: Instant): Long
}
