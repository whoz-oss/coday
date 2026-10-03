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
     *
     * ## Terminal sealing (Phase 10)
     * The `WHERE NOT a.status IN ['completed', 'failed']` guard makes the
     * primitive itself enforce "a late result never changes a sealed verdict":
     * an attempt already in a terminal status (`completed` / `failed` — the
     * full vocabulary of this result-path aggregate) matches nothing, so the
     * statement returns 0 and the sealed status and revision are left
     * untouched. Callers MUST treat 0 as "already sealed", never as a failure
     * to retry blindly.
     */
    @Query(
        """
        MATCH (a:AgentStepAttempt {id: ${'$'}id})
        WHERE NOT a.status IN ['completed', 'failed']
        SET a.status = ${'$'}status,
            a.revision = a.revision + 1,
            a.updatedAt = ${'$'}updatedAt
        RETURN count(a) AS updated
        """,
    )
    fun terminalize(id: String, status: String, updatedAt: Instant): Long
}
