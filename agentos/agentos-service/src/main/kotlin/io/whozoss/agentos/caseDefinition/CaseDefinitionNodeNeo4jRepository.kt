package io.whozoss.agentos.caseDefinition

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [CaseDefinitionNode].
 */
interface CaseDefinitionNodeNeo4jRepository : Neo4jRepository<CaseDefinitionNode, String> {

    /**
     * Returns all non-removed case definitions whose [CaseDefinitionNode.namespaceId]
     * matches the given namespace, ordered by name ascending.
     */
    @Query(
        $$"""
            MATCH (t:CaseDefinition)
            WHERE t.namespaceId = $namespaceId
              AND (t.removed IS NULL OR t.removed = false)
            RETURN t ORDER BY t.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<CaseDefinitionNode>
}
