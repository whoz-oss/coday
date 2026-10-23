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

    @Query(
        """
        MATCH (w:Workstream)
        WHERE w.organizationId = ${'$'}organizationId AND w.namespaceId = ${'$'}namespaceId
        RETURN w
        ORDER BY w.workstreamId ASC
        """,
    )
    fun findAllByOrganizationAndNamespace(organizationId: String, namespaceId: String): List<WorkstreamNode>

    /** Atomically initializes the deterministic node and never mutates an existing mapping. */
    @Query(
        """
        MERGE (w:Workstream {id: ${'$'}id})
        ON CREATE SET w.organizationId = ${'$'}organizationId,
                      w.workstreamId = ${'$'}workstreamId,
                      w.namespaceId = ${'$'}namespaceId,
                      w.name = ${'$'}name,
                      w.status = 'active',
                      w.allowedWorkflowTypes = [],
                      w.revision = 1,
                      w.createdAt = datetime(),
                      w.updatedAt = datetime()
        RETURN w
        """,
    )
    fun mergeInitialMapping(id: String, organizationId: String, workstreamId: String, namespaceId: String, name: String): WorkstreamNode
}
