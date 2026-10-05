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
     * Stable keyset pagination survives removal or status changes of earlier rows. The ID
     * breaks ties when several bindings have the same creation time. A null cursor starts a sweep.
     */
    @Query(
        $$"""
            MATCH (b:ActiveCaseResourceBinding)
            WHERE b.status IN $statuses
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
