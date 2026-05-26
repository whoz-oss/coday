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
     * always present because [io.whozoss.agentos.persistence.Neo4jChildLinkService.link]
     * is called after every save in [Neo4jCaseRepository]. Returning `c, r, ns`
     * gives SDN everything it needs to map the [CaseNode.namespace] @Relationship field.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE ns.id = $namespaceId AND (c.removed IS NULL OR c.removed = false)
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseNode>

    /**
     * Find cases in a namespace that a specific user is allowed to see.
     *
     * Access rule for Case (owner-private, FR15, WZ-32167):
     * - A user can see a case only if they have a direct ADMIN or MEMBER
     *   relation on that case node.
     * - Namespace ADMIN does NOT grant transitive visibility over all cases in
     *   the namespace. Namespace Admins and Designers must only see their own
     *   conversations (i.e. cases where they hold a direct relation).
     * - Super-admins bypass this query entirely — the controller short-circuits
     *   them upstream via [user.isAdmin] and calls [findActiveByNamespaceId]
     *   directly.
     *
     * Soft-deleted cases are filtered out. Returns cases in creation order.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace {id: $namespaceId})
            WHERE (c.removed IS NULL OR c.removed = false)
              AND EXISTS { MATCH (:User {id: $userId})-[:ADMIN|MEMBER]->(c) }
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findAccessibleByUserInNamespace(
        userId: String,
        namespaceId: String,
    ): List<CaseNode>
}
