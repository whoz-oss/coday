package io.whozoss.factory.lease.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [LeaseNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the fencing
 * counter read, the active-lease sweeps and the per-work-unit active lookup that
 * replace the former `nextval('work_unit_lease_fencing_seq')`, `FOR UPDATE`
 * sweeps and `ORDER BY fencing_token DESC LIMIT 1` query.
 */
interface SpringDataNeo4jLeaseRepository : Neo4jRepository<LeaseNode, String> {

    /**
     * The current high-water mark of the fencing counter. The caller serialises
     * acquisitions, so `max + 1` yields a strictly monotone token.
     */
    @Query("MATCH (l:WorkUnitLease) RETURN coalesce(max(l.fencingToken), 0) AS maxToken")
    fun maxFencingToken(): Long

    @Query(
        """
        MATCH (l:WorkUnitLease)
        WHERE l.organizationId = ${'$'}organizationId
          AND l.workstreamId = ${'$'}workstreamId
          AND l.status = 'active'
        RETURN l
        """,
    )
    fun findAllActiveByScope(organizationId: String, workstreamId: String): List<LeaseNode>

    @Query(
        """
        MATCH (l:WorkUnitLease)
        WHERE l.organizationId = ${'$'}organizationId
          AND l.workstreamId = ${'$'}workstreamId
          AND l.workUnitId = ${'$'}workUnitId
          AND l.status = 'active'
        RETURN l
        ORDER BY l.fencingToken DESC
        LIMIT 1
        """,
    )
    fun findActiveByWorkUnit(
        organizationId: String,
        workstreamId: String,
        workUnitId: String,
    ): LeaseNode?
}
