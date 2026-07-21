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

    /**
     * Find all non-removed prompts that belong to any of the four overlay layers for the
     * given (namespaceId, userId) pair, ordered by name:
     *   - platform         (namespaceId IS NULL AND userId IS NULL)
     *   - user-global      (userId = $userId AND namespaceId IS NULL)
     *   - namespace-shared (namespaceId = $namespaceId AND userId IS NULL)
     *   - user×namespace   (namespaceId = $namespaceId AND userId = $userId)
     *
     * Prompts linked to a disabled (enabled=false) or soft-deleted AgentConfig are excluded.
     * The OPTIONAL MATCH traverses the [:BELONGS_TO] edge to the linked AgentConfig; removed
     * agents are filtered in the OPTIONAL MATCH WHERE so they appear as null, which the
     * WITH/WHERE then treats as absent.
     */
    @Query(
        $$"""
            MATCH (p:Prompt)
            WHERE NOT COALESCE(p.removed, false)
              AND (
                (p.namespaceId IS NULL AND p.userId IS NULL)
                OR (p.userId = $userId AND p.namespaceId IS NULL)
                OR (p.namespaceId = $namespaceId AND p.userId IS NULL)
                OR (p.namespaceId = $namespaceId AND p.userId = $userId)
              )
            OPTIONAL MATCH (p)-[:BELONGS_TO]->(a:AgentConfig)
              WHERE NOT COALESCE(a.removed, false)
            WITH p, a
            WHERE p.agentConfigId IS NULL
               OR (a IS NOT NULL AND a.enabled = true)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findEffective(namespaceId: String, userId: String): List<PromptNode>

    /**
     * Soft-delete all non-removed prompts linked to the given agentConfigId.
     * Rewrites tripleKey to a tombstone to free the unique slot immediately.
     */
    @Query(
        $$"""
            MATCH (p:Prompt {agentConfigId: $agentConfigId})
            WHERE NOT COALESCE(p.removed, false)
            SET p.removed = true, p.tripleKey = 'tombstone:' + p.id
            """,
    )
    fun softDeleteByAgentConfigId(agentConfigId: String)

    /**
     * Find all non-removed prompts at an exact scope level, optionally filtered by agentConfigIds.
     * Scope is determined by the combination of namespaceId and userId (null = not set).
     * When [agentConfigIds] is null or empty, all prompts at the scope level are returned.
     */
    @Query(
        $$"""
            MATCH (p:Prompt)
            WHERE NOT COALESCE(p.removed, false)
              AND (p.namespaceId = $namespaceId OR ($namespaceId IS NULL AND p.namespaceId IS NULL))
              AND (p.userId = $userId OR ($userId IS NULL AND p.userId IS NULL))
              AND ($agentConfigIds IS NULL OR p.agentConfigId IN $agentConfigIds)
            RETURN p ORDER BY p.name ASC
            """,
    )
    fun findByScope(
        namespaceId: String?,
        userId: String?,
        agentConfigIds: List<String>?,
    ): List<PromptNode>
}
