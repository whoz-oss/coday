package io.whozoss.factory.workunit.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [WorkUnitNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the scheduling
 * scans and the status compare-and-swap that replace the former
 * `UPDATE ... WHERE revision = :expectedRevision` statement.
 */
interface SpringDataNeo4jWorkUnitRepository : Neo4jRepository<WorkUnitNode, String> {

    /** Every work unit of the tenant scope, highest priority first. */
    @Query(
        """
        MATCH (w:WorkUnit)
        WHERE w.organizationId = ${'$'}organizationId AND w.workstreamId = ${'$'}workstreamId
        RETURN w
        ORDER BY w.priority DESC, w.createdAt ASC
        """,
    )
    fun findAllByScope(organizationId: String, workstreamId: String): List<WorkUnitNode>

    /**
     * The reclaimable work units of the tenant scope (status `created` or
     * `failed`), ordered by scheduling precedence. The `notBefore` deferral is
     * applied by the caller so the temporal comparison stays in Kotlin.
     */
    @Query(
        """
        MATCH (w:WorkUnit)
        WHERE w.organizationId = ${'$'}organizationId
          AND w.workstreamId = ${'$'}workstreamId
          AND w.status IN ['created', 'failed']
        RETURN w
        ORDER BY w.priority DESC, w.createdAt ASC
        """,
    )
    fun findReclaimable(organizationId: String, workstreamId: String): List<WorkUnitNode>

    /**
     * Atomically transitions the work unit whose stored `revision` equals
     * [expectedRevision], incrementing it by one. Returns the number of updated
     * nodes (0 or 1).
     */
    @Query(
        """
        MATCH (w:WorkUnit {id: ${'$'}id})
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
}
