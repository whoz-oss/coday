package io.whozoss.agentos.prompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [PromptNode].
 */
interface PromptNodeNeo4jRepository : Neo4jRepository<PromptNode, String> {
    /**
     * Find all non-removed prompts scoped to the given namespace.
     * Uses the indexed scalar [PromptNode.namespaceId] property for efficient filtering.
     */
    @Query(
        $$"""
            MATCH (p:Prompt)
            WHERE p.namespaceId = $namespaceId
              AND NOT COALESCE(p.removed, false)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<PromptNode>

    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL).
     */
    @Query(
        $$"""
            MATCH (p:Prompt)
            WHERE p.namespaceId IS NULL
              AND NOT COALESCE(p.removed, false)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findActivePlatform(): List<PromptNode>
}
