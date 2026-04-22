package io.whozoss.agentos.caseFlow

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseNode].
 */
interface CaseNodeNeo4jRepository : Neo4jRepository<CaseNode, String> {
    /**
     * Find all non-removed cases belonging to a namespace, ordered by creation time.
     *
     * Traverses the BELONGS_TO edge and filters by the Namespace id. The edge is
     * always present because [linkCaseToNamespace] is called after every save.
     * Returning `c, r, ns` gives SDN everything it needs to map the
     * [CaseNode.namespace] @Relationship field.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE ns.id = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseNode>

    /**
     * Creates the `BELONGS_TO` relationship from a Case node to its Namespace node.
     *
     * Called after saving a Case. Using an explicit query avoids SDN writing
     * stub [io.whozoss.agentos.namespace.NamespaceNode] properties (empty name/description) onto the existing
     * Namespace node when the relationship is expressed via the @Relationship field.
     */
    @Query(
        $$"""MATCH (c:Case {id: $caseId})
            MATCH (ns:Namespace {id: $namespaceId})
            MERGE (c)-[:BELONGS_TO]->(ns)
            """,
    )
    fun linkCaseToNamespace(
        caseId: String,
        namespaceId: String,
    )

    @Query(
        $$"""MATCH (c:Case {id: $caseId})
            MATCH (u:User {id: $userId})
            MERGE (c)-[:CREATED_BY]->(u)
            """,
    )
    fun linkCaseToUser(
        caseId: String,
        userId: String,
    )
}
