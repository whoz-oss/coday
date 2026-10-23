package io.whozoss.factory.environment.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [WorkEnvironmentNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the scope /
 * workflow lookups and the descriptor compare-and-swap that replace the former
 * `payload->>'workflowId'` scan and `UPDATE ... WHERE revision` statement.
 */
interface SpringDataNeo4jWorkEnvironmentRepository : Neo4jRepository<WorkEnvironmentNode, String> {

    @Query(
        """
        MATCH (e:WorkEnvironment)
        WHERE e.organizationId = ${'$'}organizationId AND e.workstreamId = ${'$'}workstreamId
        RETURN e
        ORDER BY e.environmentId ASC
        """,
    )
    fun findAllByScope(organizationId: String, workstreamId: String): List<WorkEnvironmentNode>

    /** The most recently updated environment of a workflow, or `null`. */
    @Query(
        """
        MATCH (e:WorkEnvironment)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.workstreamId = ${'$'}workstreamId
          AND e.workflowId = ${'$'}workflowId
        RETURN e
        ORDER BY e.updatedAt DESC
        LIMIT 1
        """,
    )
    fun findLatestByWorkflowId(
        organizationId: String,
        workstreamId: String,
        workflowId: String,
    ): WorkEnvironmentNode?

    /**
     * Atomically updates the descriptor whose stored `revision` equals
     * [expectedRevision], incrementing it by one. Returns the number of updated
     * nodes (0 or 1).
     */
    @Query(
        """
        MATCH (e:WorkEnvironment {id: ${'$'}id})
        WHERE e.revision = ${'$'}expectedRevision
        SET e.status = ${'$'}status,
            e.revision = e.revision + 1,
            e.payload = ${'$'}payload,
            e.updatedAt = ${'$'}updatedAt
        RETURN count(e) AS updated
        """,
    )
    fun casUpdateState(
        id: String,
        expectedRevision: Int,
        status: String,
        payload: String,
        updatedAt: Instant,
    ): Long
}
