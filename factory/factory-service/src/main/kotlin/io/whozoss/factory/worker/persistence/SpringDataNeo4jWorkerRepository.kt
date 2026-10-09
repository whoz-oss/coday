package io.whozoss.factory.worker.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [WorkerNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the graph-native
 * operations the adapter needs: the organization-scoped listing, the status
 * compare-and-swap and the liveness heartbeat, both of which replace the former
 * `UPDATE ... WHERE revision = :expected` statements.
 */
interface SpringDataNeo4jWorkerRepository : Neo4jRepository<WorkerNode, String> {

    @Query(
        """
        MATCH (w:Worker)
        WHERE w.organizationId = ${'$'}organizationId
        RETURN w
        ORDER BY w.workerId ASC
        """,
    )
    fun findAllByOrganization(organizationId: String): List<WorkerNode>

    /**
     * Atomically transitions the worker whose stored `revision` equals
     * [expectedRevision], incrementing it by one. Returns the number of updated
     * nodes (0 or 1) so the caller can distinguish a conflict from a missing row.
     */
    @Query(
        """
        MATCH (w:Worker {id: ${'$'}id})
        WHERE w.revision = ${'$'}expectedRevision
        SET w.status = ${'$'}status,
            w.revision = w.revision + 1,
            w.updatedAt = ${'$'}updatedAt
        RETURN count(w) AS updated
        """,
    )
    fun casUpdateStatus(
        id: String,
        expectedRevision: Int,
        status: String,
        updatedAt: Instant,
    ): Long

    /**
     * Records liveness: refreshes `lastHeartbeatAt`, bumps `revision` and
     * returns the number of updated nodes. Compare-and-swap when
     * [expectedRevision] is supplied (a `null` value skips the revision guard).
     */
    @Query(
        """
        MATCH (w:Worker {id: ${'$'}id})
        WHERE (${'$'}expectedRevision IS NULL OR w.revision = ${'$'}expectedRevision)
        SET w.lastHeartbeatAt = ${'$'}heartbeatAt,
            w.revision = w.revision + 1,
            w.updatedAt = ${'$'}updatedAt
        RETURN count(w) AS updated
        """,
    )
    fun updateHeartbeat(
        id: String,
        heartbeatAt: Instant,
        expectedRevision: Int?,
        updatedAt: Instant,
    ): Long
}
