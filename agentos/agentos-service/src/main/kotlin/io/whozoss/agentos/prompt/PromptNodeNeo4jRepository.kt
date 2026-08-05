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
     *   - user-global      (userId = userId AND namespaceId IS NULL)
     *   - namespace-shared (namespaceId = namespaceId AND userId IS NULL)
     *   - user×namespace   (namespaceId = namespaceId AND userId = userId)
     *
     * Access control is applied per-prompt based on whether it is linked to an agent:
     *
     * Case 1 — Prompt WITH an agent (agentConfigId IS NOT NULL):
     *   The agent must exist, be enabled, AND the user must have access via:
     *     a) super-admin: u.isAdmin = true
     *     b) user-group:  user is MEMBER|ADMIN of a UserGroup in the namespace
     *                     AND the agent is DEPLOYED_TO that same UserGroup
     *   There is NO namespace-direct fallback: if an agent is not deployed to any
     *   user-group, only super-admins can see its prompts.
     *
     * Case 2 — Prompt WITHOUT an agent (agentConfigId IS NULL):
     *   The user must have access to the prompt's namespace via:
     *     a) no namespace (platform or user-global, p.namespaceId IS NULL): always included
     *     b) super-admin: u.isAdmin = true
     *     c) namespace membership: user is MEMBER|ADMIN of the namespace node in the graph
     *
     * Performance:
     *   - u and ns resolved once via UNIQUE id constraints.
     *   - User group memberships collected once into accessibleGroupIds before scanning prompts.
     *   - isMemberOfNs and isAdmin pre-computed once, not re-evaluated per prompt row.
     *   - MATCH (p:Prompt) runs with 1 input row, using namespaceId/userId indexes.
     *   - BELONGS_TO traversal guarded by agentConfigId IS NOT NULL to skip agent-less prompts.
     */
    @Query(
        $$"""
            OPTIONAL MATCH (u:User)
              WHERE u.id = $userId AND NOT COALESCE(u.removed, false)
            OPTIONAL MATCH (ns:Namespace)
              WHERE ns.id = $namespaceId AND NOT COALESCE(ns.removed, false)
            OPTIONAL MATCH (u)-[:MEMBER|ADMIN]->(ag:UserGroup)-[:BELONGS_TO]->(ns)
              WHERE NOT COALESCE(ag.removed, false)
            WITH u, ns,
                 COALESCE(u.isAdmin, false) AS isAdmin,
                 EXISTS { MATCH (u)-[:MEMBER|ADMIN]->(ns) } AS isMemberOfNs,
                 collect(ag.id) AS accessibleGroupIds
            MATCH (p:Prompt)
            WHERE NOT COALESCE(p.removed, false)
              AND (
                (p.namespaceId IS NULL AND p.userId IS NULL)
                OR (p.userId = $userId AND p.namespaceId IS NULL)
                OR (p.namespaceId = $namespaceId AND p.userId IS NULL)
                OR (p.namespaceId = $namespaceId AND p.userId = $userId)
              )
            OPTIONAL MATCH (p)-[:BELONGS_TO]->(a:AgentConfig)
              WHERE p.agentConfigId IS NOT NULL
                AND NOT COALESCE(a.removed, false)
            WITH p, a, isAdmin, isMemberOfNs, accessibleGroupIds
            WHERE
              (
                p.agentConfigId IS NOT NULL
                AND a IS NOT NULL AND a.enabled = true
                AND (
                  isAdmin
                  OR EXISTS {
                    MATCH (a)-[:DEPLOYED_TO]->(g:UserGroup)
                    WHERE g.id IN accessibleGroupIds
                  }
                )
              )
              OR
              (
                p.agentConfigId IS NULL
                AND (
                  p.namespaceId IS NULL
                  OR isAdmin
                  OR isMemberOfNs
                )
              )
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
