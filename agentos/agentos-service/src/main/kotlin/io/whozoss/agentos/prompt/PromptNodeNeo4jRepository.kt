package io.whozoss.agentos.prompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [PromptNode].
 */
interface PromptNodeNeo4jRepository : Neo4jRepository<PromptNode, String> {
    /**
     * Find all non-removed namespace-shared prompts (userId IS NULL) for the given namespace.
     * User-scoped overlays (userId != null) are intentionally excluded.
     */
    @Query(
        $$"""
            MATCH (p:Prompt)
            WHERE p.namespaceId = $namespaceId
              AND p.userId IS NULL
              AND NOT COALESCE(p.removed, false)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<PromptNode>

    /**
     * Find all non-removed platform-level prompts (namespaceId IS NULL AND userId IS NULL).
     */
    @Query(
        """
            MATCH (p:Prompt)
            WHERE p.namespaceId IS NULL
              AND p.userId IS NULL
              AND NOT COALESCE(p.removed, false)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findActivePlatform(): List<PromptNode>

    /**
     * Find all non-removed prompts scoped to a user, ordered by name.
     */
    @Query(
        $$"""
            MATCH (p:Prompt)
            WHERE p.userId = $userId
              AND NOT COALESCE(p.removed, false)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findActiveByUserId(userId: String): List<PromptNode>

    /**
     * Find a single non-removed prompt matched by its [PromptNode.tripleKey] discriminator.
     */
    @Query(
        $$"""
            MATCH (p:Prompt {tripleKey: $tripleKey})
            WHERE NOT COALESCE(p.removed, false)
            RETURN p LIMIT 1
            """,
    )
    fun findActiveByTripleKey(tripleKey: String): PromptNode?
}
