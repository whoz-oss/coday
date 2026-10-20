package io.whozoss.agentos.git

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [CaseResourceBindingNode].
 */
interface CaseResourceBindingNodeNeo4jRepository : Neo4jRepository<CaseResourceBindingNode, String> {
    @Query($$"MATCH (b:CaseResourceBinding {id: $id}) SET b:ActiveCaseResourceBinding")
    fun setActive(id: String)

    @Query($$"MATCH (b:CaseResourceBinding {id: $id}) REMOVE b:ActiveCaseResourceBinding")
    fun setInactive(id: String)

    /** Behind [CaseResourceBindingRepository.deleteByParent]: a binding's parent is its namespace. */
    @Query(
        $$"MATCH (b:CaseResourceBinding:ActiveCaseResourceBinding) WHERE b.namespaceId = $namespaceId REMOVE b:ActiveCaseResourceBinding",
    )
    fun setInactiveByNamespaceId(namespaceId: String)

    /**
     * The active binding of a root case, found through the index of the
     * `case_resource_binding_active_root_case_unique` constraint.
     */
    @Query(
        $$"""
            MATCH (b:ActiveCaseResourceBinding {rootCaseId: $rootCaseId})
            RETURN b
            LIMIT 1
            """,
    )
    fun findActiveByRootCaseId(rootCaseId: String): CaseResourceBindingNode?

    /**
     * First page of a binding sweep, oldest first. The ID breaks ties when several bindings have the
     * same creation time.
     *
     * `b.created IS NOT NULL` lets the planner seek on the `case_resource_binding_active_status`
     * index, so a one-status sweep with nothing waiting reads nothing.
     */
    @Query(
        $$"""
            MATCH (b:ActiveCaseResourceBinding)
            WHERE b.status IN $statuses AND b.created IS NOT NULL
            RETURN b ORDER BY b.created ASC, b.id ASC
            LIMIT $limit
            """,
    )
    fun findActiveByStatusIn(
        statuses: Collection<String>,
        limit: Int,
    ): List<CaseResourceBindingNode>

    /**
     * Next page of a binding sweep, strictly after the cursor. Stable keyset pagination survives
     * removal or status changes of earlier rows.
     *
     * The planner walks the `case_resource_binding_active_created_id` index in order and stops after
     * `limit` matches, instead of reading and sorting every row of the statuses. Two details keep
     * that plan: `b.id IS NOT NULL` makes the composite index usable, and the status filter comes
     * after the ordered `WITH`, so the status index is not a candidate.
     */
    @Query(
        $$"""
            MATCH (b:ActiveCaseResourceBinding)
            WHERE b.created >= $afterCreated AND b.id IS NOT NULL
              AND (b.created > $afterCreated OR b.id > $afterId)
            WITH b ORDER BY b.created ASC, b.id ASC
            WHERE b.status IN $statuses
            RETURN b ORDER BY b.created ASC, b.id ASC
            LIMIT $limit
            """,
    )
    fun findActiveByStatusInAfter(
        statuses: Collection<String>,
        limit: Int,
        afterCreated: Instant,
        afterId: String,
    ): List<CaseResourceBindingNode>

    /** Active bindings of a namespace, behind [CaseResourceBindingRepository.findByParent]. */
    @Query(
        $$"""
            MATCH (b:CaseResourceBinding:ActiveCaseResourceBinding)
            WHERE b.namespaceId = $namespaceId
            RETURN b ORDER BY b.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseResourceBindingNode>
}
