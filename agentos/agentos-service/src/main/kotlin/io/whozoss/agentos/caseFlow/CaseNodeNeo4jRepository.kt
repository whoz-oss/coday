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
     * Access rule for Case (owner-private, FR15):
     * - Direct relation on the case: ADMIN or MEMBER -> user can see it
     * - Transitive via namespace: only ADMIN on namespace -> user can see all cases
     *   (namespace MEMBER does NOT gain transitive READ on cases)
     *
     * Soft-deleted cases are filtered out. Returns cases in creation order.
     *
     * Super-admins do not use this query -- the controller short-circuits them
     * upstream via [io.whozoss.agentos.permissions.PermissionService.hasPermission]
     * on the parent namespace.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace {id: $namespaceId})
            WHERE (c.removed IS NULL OR c.removed = false)
              AND (
                EXISTS { MATCH (:User {id: $userId})-[:ADMIN|MEMBER]->(c) }
                OR EXISTS { MATCH (:User {id: $userId})-[:ADMIN]->(ns) }
              )
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findAccessibleByUserInNamespace(
        userId: String,
        namespaceId: String,
    ): List<CaseNode>

    /**
     * Find all non-removed cases concerning a user across all namespaces.
     *
     * A case concerns a user when they have a direct ADMIN or MEMBER relation on it.
     * Namespace-level ADMIN is intentionally excluded: a namespace admin should only
     * see their own threads here, not every case in the namespace.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE (c.removed IS NULL OR c.removed = false)
              AND EXISTS { MATCH (:User {id: $userId})-[:ADMIN|MEMBER]->(c) }
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findConcerningUser(userId: String): List<CaseNode>

    /**
     * Find all non-removed cases concerning a user scoped to a single namespace.
     *
     * Same permission rule as [findConcerningUser] (direct ADMIN or MEMBER on the case),
     * but restricted to the given namespace.
     */
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace {id: $namespaceId})
            WHERE (c.removed IS NULL OR c.removed = false)
              AND EXISTS { MATCH (:User {id: $userId})-[:ADMIN|MEMBER]->(c) }
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findConcerningUserInNamespace(userId: String, namespaceId: String): List<CaseNode>
}
