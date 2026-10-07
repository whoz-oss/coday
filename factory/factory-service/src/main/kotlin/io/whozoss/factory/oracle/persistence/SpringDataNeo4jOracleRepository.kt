package io.whozoss.factory.oracle.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [OracleExecutionNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], this interface declares
 * the two graph-native operations the [Neo4jOracleExecutionRepository] port
 * adapter needs:
 *  - [casUpdateStatus]: the atomic compare-and-swap that replaces the SQL
 *    `UPDATE ... WHERE revision = :expected`;
 *  - [findByIdempotencyKey]: an indexed property lookup replacing the former
 *    `payload->>'idempotencyKey'` JSONB scan.
 */
interface SpringDataNeo4jOracleRepository : Neo4jRepository<OracleExecutionNode, String> {

    /**
     * Atomically transitions the execution whose stored `revision` equals
     * [expectedRevision], incrementing it by one.
     *
     * Returns the number of nodes updated (0 or 1), so the caller can
     * distinguish a revision conflict from a missing execution.
     */
    @Query(
        """
        MATCH (e:OracleExecution {id: ${'$'}id})
        WHERE e.revision = ${'$'}expectedRevision
        SET e.status = ${'$'}status,
            e.revision = e.revision + 1,
            e.evidenceId = coalesce(${'$'}evidenceId, e.evidenceId),
            e.artifactId = coalesce(${'$'}artifactId, e.artifactId),
            e.payload = coalesce(${'$'}payload, e.payload),
            e.updatedAt = ${'$'}updatedAt
        RETURN count(e) AS updated
        """,
    )
    fun casUpdateStatus(
        id: String,
        expectedRevision: Int,
        status: String,
        evidenceId: String?,
        artifactId: String?,
        payload: String?,
        updatedAt: java.time.Instant,
    ): Long

    @Query(
        """
        MATCH (e:OracleExecution)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.workstreamId = ${'$'}workstreamId
          AND e.idempotencyKey = ${'$'}idempotencyKey
        RETURN e
        ORDER BY e.createdAt ASC
        LIMIT 1
        """,
    )
    fun findByIdempotencyKey(
        organizationId: String,
        workstreamId: String,
        idempotencyKey: String,
    ): OracleExecutionNode?

    @Query(
        """
        MATCH (e:OracleExecution)
        WHERE e.organizationId = ${'$'}organizationId AND e.workstreamId = ${'$'}workstreamId
        RETURN count(e) AS count
        """,
    )
    fun countByScope(organizationId: String, workstreamId: String): Long
}
