package io.whozoss.agentos.scheduledPrompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [ScheduledPromptNode].
 */
interface ScheduledPromptNodeNeo4jRepository : Neo4jRepository<ScheduledPromptNode, String> {

    /**
     * Find all non-removed namespace-shared scheduled prompts (userId IS NULL) for the given namespace.
     * User-scoped overlays (userId != null) are intentionally excluded.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE sp.namespaceId = $namespaceId
              AND sp.userId IS NULL
              AND NOT COALESCE(sp.removed, false)
            RETURN sp ORDER BY sp.name ASC
            """,
    )
    fun findActiveByNamespaceId(namespaceId: String): List<ScheduledPromptNode>

    /** Find all non-removed platform-level scheduled prompts (namespaceId IS NULL AND userId IS NULL). */
    @Query(
        """
            MATCH (sp:ScheduledPrompt)
            WHERE sp.namespaceId IS NULL
              AND sp.userId IS NULL
              AND NOT COALESCE(sp.removed, false)
            RETURN sp ORDER BY sp.name ASC
            """,
    )
    fun findActivePlatform(): List<ScheduledPromptNode>

    /** Find a single non-removed scheduled prompt matched by its [ScheduledPromptNode.tripleKey] discriminator. */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt {tripleKey: $tripleKey})
            WHERE NOT COALESCE(sp.removed, false)
            RETURN sp LIMIT 1
            """,
    )
    fun findActiveByTripleKey(tripleKey: String): ScheduledPromptNode?

    /**
     * Find all non-removed scheduled prompts that belong to any of the four overlay layers for the
     * given (namespaceId, userId) pair, ordered by name.
     *
     * Access control: the user must be super-admin OR a member of a UserGroup to which the agent
     * is DEPLOYED_TO. There is no bifurcation because agentConfigId is always present.
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
                 collect(ag.id) AS accessibleGroupIds
            MATCH (sp:ScheduledPrompt)
            WHERE NOT COALESCE(sp.removed, false)
              AND (
                (sp.namespaceId IS NULL AND sp.userId IS NULL)
                OR (sp.userId = $userId AND sp.namespaceId IS NULL)
                OR (sp.namespaceId = $namespaceId AND sp.userId IS NULL)
                OR (sp.namespaceId = $namespaceId AND sp.userId = $userId)
              )
            MATCH (sp)-[:BELONGS_TO]->(a:AgentConfig)
              WHERE NOT COALESCE(a.removed, false)
                AND a.enabled = true
            WITH sp, a, isAdmin, accessibleGroupIds
            WHERE isAdmin
              OR EXISTS {
                MATCH (a)-[:DEPLOYED_TO]->(g:UserGroup)
                WHERE g.id IN accessibleGroupIds
              }
            RETURN sp ORDER BY sp.name ASC
            """,
    )
    fun findEffective(namespaceId: String, userId: String): List<ScheduledPromptNode>

    /**
     * Find all non-removed scheduled prompts at an exact scope level, optionally filtered by agentConfigIds.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE NOT COALESCE(sp.removed, false)
              AND (sp.namespaceId = $namespaceId OR ($namespaceId IS NULL AND sp.namespaceId IS NULL))
              AND (sp.userId = $userId OR ($userId IS NULL AND sp.userId IS NULL))
              AND ($agentConfigIds IS NULL OR sp.agentConfigId IN $agentConfigIds)
            RETURN sp ORDER BY sp.name ASC
            """,
    )
    fun findByScope(
        namespaceId: String?,
        userId: String?,
        agentConfigIds: List<String>?,
    ): List<ScheduledPromptNode>

    /**
     * Find all enabled scheduled prompts due for execution: nextRunAt <= now, ordered ASC.
     */
    @Query(
        "MATCH (sp:ScheduledPrompt) " +
            "WHERE NOT COALESCE(sp.removed, false) " +
            "AND sp.enabled = true " +
            "AND sp.nextRunAt <= :now " +
            "RETURN sp ORDER BY sp.nextRunAt ASC",
    )
    fun findDue(now: Instant): List<ScheduledPromptNode>

    /**
     * Optimistic-CAS update of nextRunAt.
     * Sets nextRunAt = :nextSlot only when the current stored value equals :currentSlot.
     *
     * Returns true if the update was applied (exactly one node matched the CAS condition).
     */
    @Query(
        "MATCH (sp:ScheduledPrompt) " +
            "WHERE sp.id = :id AND sp.nextRunAt = :currentSlot " +
            "SET sp.nextRunAt = :nextSlot " +
            "RETURN count(sp) > 0",
    )
    fun advanceNextRunAt(id: String, currentSlot: Instant, nextSlot: Instant): Boolean
}
