package io.whozoss.agentos.caseFlow

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace {id: $namespaceId})
            WHERE (c.removed IS NULL OR c.removed = false)
              AND EXISTS { MATCH (:User {id: $userId})-[:ADMIN|MEMBER]->(c) }
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findConcerningUserInNamespace(
        userId: String,
        namespaceId: String,
    ): List<CaseNode>

    /**
     * Find all active, non-terminal sub-cases whose parentCaseId matches, with their namespace edge.
     *
     * Excludes sub-cases that are already in a terminal status (KILLED or ERROR) — killing
     * a parent must not overwrite the diagnostic status of sub-cases that already completed.
     *
     * The BELONGS_TO edge is always present (written by [Neo4jCaseRepository.save]),
     * so MATCH (not OPTIONAL MATCH) is safe here.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (c:Case)-[r:BELONGS_TO]->(ns:Namespace)
            WHERE c.parentCaseId = $parentCaseId
              AND (c.removed IS NULL OR c.removed = false)
              AND NOT c.status IN ['KILLED', 'ERROR']
            RETURN c, r, ns ORDER BY c.created ASC
            """,
    )
    fun findActiveByParentCaseId(parentCaseId: String): List<CaseNode>

    /**
     * Count the number of ancestor hops from [caseId] up through the PARENT_OF chain.
     *
     * Returns 0 when [caseId] has no parent, 1 when it has one parent, etc.
     * Used by [io.whozoss.agentos.caseFlow.CaseServiceImpl.startSubCase] to enforce a
     * maximum delegation depth before creating a new sub-case.
     *
     * Traverses the [:PARENT_OF] graph edges written by [linkParentToChild].
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (c:Case {id: $caseId})
            OPTIONAL MATCH path = (c)<-[:PARENT_OF*..10]-(ancestor:Case)
            RETURN coalesce(length(path), 0) AS depth
            ORDER BY depth DESC LIMIT 1
            """,
    )
    fun countAncestorDepth(caseId: String): Int

    /**
     * Find all active, non-terminal descendants of [caseId] via the [:PARENT_OF] graph edges,
     * up to a maximum depth of 10 hops.
     *
     * Returns descendants leaves-first (deepest paths first) so that callers killing the
     * tree process children before their parents. Excludes cases already in a terminal
     * status (KILLED or ERROR) — there is no point signalling a case that has already stopped.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH path = (root:Case)
            WHERE root.id = $caseId
            WITH root
            MATCH path = (root)-[:PARENT_OF*1..10]->(descendant:Case)
            WHERE (descendant.removed IS NULL OR descendant.removed = false)
              AND NOT descendant.status IN ['KILLED', 'ERROR']
            RETURN descendant
            ORDER BY length(path) DESC
            """,
    )
    fun findActiveDescendants(caseId: String): List<CaseNode>

    /**
     * Creates the [:PARENT_OF] relationship from [parentCaseId] to [childCaseId].
     *
     * Called by [io.whozoss.agentos.caseFlow.Neo4jCaseRepository] after creating a sub-case
     * so that [countAncestorDepth] can traverse the chain.
     */
    @Query(
        $$"""MATCH (parent:Case {id: $parentCaseId}), (child:Case {id: $childCaseId})
            MERGE (parent)-[:PARENT_OF]->(child)
            """,
    )
    fun linkParentToChild(
        @Param("parentCaseId") parentCaseId: String,
        @Param("childCaseId") childCaseId: String,
    )

    // -------------------------------------------------------------------------
    // Starred / favorite — per-user [:STARRED] relationship on Case nodes.
    //
    // The [:STARRED] edge is orthogonal to [:ADMIN]/[:MEMBER]: role transitions
    // (promote/demote) leave it untouched. The MATCH guard on [:ADMIN|MEMBER]
    // prevents orphaned [:STARRED] edges for users with no direct permission.
    // -------------------------------------------------------------------------

    /**
     * Creates a `[:STARRED]` edge `(u)-[:STARRED]->(c:Case)` — only when a direct
     * `[:ADMIN]` or `[:MEMBER]` edge already exists on the case (guard against orphans).
     *
     * Returns the number of `[:Case]` nodes matched (0 = user has no direct
     * permission edge, so no star was persisted).
     */
    @Query(
        $$"""MATCH (u:User {id: $userId})-[:ADMIN|MEMBER]->(c:Case {id: $caseId})
            MERGE (u)-[:STARRED]->(c)
            RETURN count(c)
            """,
    )
    fun mergeStarred(
        @Param("userId") userId: String,
        @Param("caseId") caseId: String,
    ): Long

    /**
     * Removes the `[:STARRED]` edge between the user and the case, if it exists.
     *
     * Returns the number of `[:ADMIN]/[:MEMBER]` edges matched (0 = user has no
     * direct permission edge; safe no-op).
     */
    @Query(
        $$"""MATCH (u:User {id: $userId})-[:ADMIN|MEMBER]->(c:Case {id: $caseId})
            OPTIONAL MATCH (u)-[s:STARRED]->(c)
            DELETE s
            RETURN count(c)
            """,
    )
    fun deleteStarred(
        @Param("userId") userId: String,
        @Param("caseId") caseId: String,
    ): Long

    /**
     * Returns one entry per case the user has a direct `[:ADMIN]`/`[:MEMBER]` edge on, collapsed at
     * the Cypher level so the caller needs no manual de-duplication. Each map holds:
     * - `caseId` (String) — the case id,
     * - `relations` (List<String>) — the distinct edge types (`["ADMIN"]`, `["MEMBER"]`, or both),
     * - `starred` (Boolean) — `true` when a `[:STARRED]` edge also exists between the user and the case.
     *
     * Built as a single-column `collect` of maps: Spring Data Neo4j rejects a multi-column
     * `RETURN a, b, c` ("Records with more than one value cannot be converted without a mapper"),
     * so the whole result is returned as one `List<Map>` value it can map without a custom converter.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(c:Case)
            WITH u, c, collect(DISTINCT type(r)) AS relations
            RETURN collect({
                caseId: c.id,
                relations: relations,
                starred: EXISTS { (u)-[:STARRED]->(c) }
            })
            """,
    )
    fun findDirectRelations(
        @Param("userId") userId: String,
    ): List<Map<String, Any>>
}
