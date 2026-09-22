package io.whozoss.agentos.git

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

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
     * Stable keyset pagination survives removal or status changes of earlier rows. The ID
     * breaks ties when several bindings have the same creation time. A null cursor starts a sweep.
     */
    @Query(
        $$"""
            MATCH (b:CaseResourceBinding)
            WHERE b.status IN $statuses AND (b.removed IS NULL OR b.removed = false)
              AND ($afterCreated IS NULL OR b.created > $afterCreated
                   OR (b.created = $afterCreated AND b.id > $afterId))
            RETURN b ORDER BY b.created ASC, b.id ASC
            LIMIT $limit
            """,
    )
    fun findActiveByStatusIn(
        statuses: Collection<String>,
        limit: Int,
        afterCreated: Instant?,
        afterId: String?,
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
