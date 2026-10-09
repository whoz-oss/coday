package io.whozoss.factory.workstream.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [WorkstreamNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the
 * organization-scoped listing the adapter needs, replacing the former
 * `SELECT ... WHERE organization_id = :org ORDER BY workstream_id` query.
 */
interface SpringDataNeo4jWorkstreamRepository : Neo4jRepository<WorkstreamNode, String> {

    /** Every workstream of the organization, ordered by its stable workstream id. */
    @Query(
        """
        MATCH (w:Workstream)
        WHERE w.organizationId = ${'$'}organizationId
        RETURN w
        ORDER BY w.workstreamId ASC
        """,
    )
    fun findAllByOrganization(organizationId: String): List<WorkstreamNode>
}
