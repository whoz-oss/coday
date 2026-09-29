package io.whozoss.agentos.git

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseResourceBindingNode].
 */
interface CaseResourceBindingNodeNeo4jRepository : Neo4jRepository<CaseResourceBindingNode, String> {
    @Query($$"MATCH (b:CaseResourceBinding {id: $id}) SET b:ActiveCaseResourceBinding")
    fun setActive(id: String)

    @Query($$"MATCH (b:CaseResourceBinding {id: $id}) REMOVE b:ActiveCaseResourceBinding")
    fun setInactive(id: String)

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

    /** Active bindings of a namespace, used by lifecycle sweeps and by namespace deletion. */
    @Query(
        $$"""
            MATCH (b:CaseResourceBinding:ActiveCaseResourceBinding)
            WHERE b.namespaceId = $namespaceId
            RETURN b ORDER BY b.created ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseResourceBindingNode>
}
