package io.whozoss.agentos.git

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseResourceBindingNode].
 */
interface CaseResourceBindingNodeNeo4jRepository : Neo4jRepository<CaseResourceBindingNode, String> {
    /**
     * The active binding of a root case. Matching [CaseResourceBindingNode.activeRootCaseKey]
     * implies the row is active, so no `removed` predicate is needed.
     */
    @Query(
        $$"""
            MATCH (b:CaseResourceBinding {activeRootCaseKey: $rootCaseId})
            RETURN b
            LIMIT 1
            """,
    )
    fun findActiveByRootCaseKey(rootCaseId: String): CaseResourceBindingNode?

    /**
     * Active bindings in one of [statuses], oldest first, capped by [limit].
     *
     * Drives the provisioning sweep. Ordering by creation keeps a long queue fair instead of
     * letting a repeatedly failing workspace starve the ones behind it.
     */
    @Query(
        $$"""
            MATCH (b:CaseResourceBinding)
            WHERE b.status IN $statuses AND (b.removed IS NULL OR b.removed = false)
            RETURN b ORDER BY b.created ASC
            LIMIT $limit
            """,
    )
    fun findActiveByStatusIn(
        statuses: Collection<String>,
        limit: Int,
    ): List<CaseResourceBindingNode>

    /** Active bindings of a namespace, used by lifecycle sweeps and by namespace deletion. */
    @Query(
        $$"""
            MATCH (b:CaseResourceBinding)
            WHERE b.namespaceId = $namespaceId AND (b.removed IS NULL OR b.removed = false)
            RETURN b ORDER BY b.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseResourceBindingNode>
}
