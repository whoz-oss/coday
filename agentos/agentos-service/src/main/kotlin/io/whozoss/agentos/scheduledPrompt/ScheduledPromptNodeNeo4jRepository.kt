package io.whozoss.agentos.scheduledPrompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.transaction.annotation.Transactional
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
     * Find scheduled prompts at an exact scope level, optionally filtered by agentConfigIds.
     * When [withRemoved] is true, soft-deleted entries are included.
     * When [modifiedSince] is provided, only entries modified after that instant are returned.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE ($withRemoved OR NOT COALESCE(sp.removed, false))
              AND (sp.namespaceId = $namespaceId OR ($namespaceId IS NULL AND sp.namespaceId IS NULL))
              AND (sp.userId = $userId OR ($userId IS NULL AND sp.userId IS NULL))
              AND ($agentConfigIds IS NULL OR sp.agentConfigId IN $agentConfigIds)
              AND ($modifiedSince IS NULL OR sp.modified > $modifiedSince)
            RETURN sp ORDER BY sp.name ASC
            """,
    )
    fun findByScope(
        namespaceId: String?,
        userId: String?,
        agentConfigIds: List<String>?,
        withRemoved: Boolean = false,
        modifiedSince: Instant? = null,
    ): List<ScheduledPromptNode>

    /**
     * Find all enabled scheduled prompts due for execution: nextRunAt <= now, ordered ASC.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE NOT COALESCE(sp.removed, false)
              AND sp.enabled = true
              AND sp.nextRunAt <= $now
            RETURN sp ORDER BY sp.nextRunAt ASC
            """,
    )
    fun findDue(now: Instant): List<ScheduledPromptNode>

    /**
     * Optimistic update of nextRunAt.
     * Sets nextRunAt = :nextSlot only when the current stored value equals :currentSlot.
     *
     * Returns true if the update was applied (exactly one node matched the condition).
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE sp.id = $id AND sp.nextRunAt = $currentSlot
            SET sp.nextRunAt = $nextSlot
            RETURN count(sp) > 0
            """,
    )
    fun advanceNextRunAt(id: String, currentSlot: Instant, nextSlot: Instant): Boolean

    /**
     * Targeted update of the enabled flag — does NOT touch any other field.
     * Safe to call concurrently: only touches `enabled`, leaves `nextRunAt` and all
     * other properties untouched, so it cannot overwrite a concurrent advance of nextRunAt.
     * Also bumps `modified` so delta-sync clients observe the change via [findByScope] with `modifiedSince`.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE sp.id = $id AND NOT COALESCE(sp.removed, false)
            SET sp.enabled = $enabled, sp.modified = datetime()
            """,
    )
    fun updateEnabled(id: String, enabled: Boolean)

    /**
     * Disable all non-removed scheduled prompts referencing the given agentConfigId.
     * Also bumps `modified` on each affected node so delta-sync clients observe the change
     * via [findByScope] with `modifiedSince`.
     * Returns the number of nodes updated.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE sp.agentConfigId = $agentConfigId
              AND NOT COALESCE(sp.removed, false)
              AND sp.enabled = true
            SET sp.enabled = false, sp.modified = datetime()
            RETURN count(sp)
            """,
    )
    fun disableByAgentConfigId(agentConfigId: String): Int

    /**
     * Returns true if at least one non-removed ScheduledPrompt references the given promptTemplateId.
     * Read-only: no write intent, no dirty checking.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE sp.promptTemplateId = $promptTemplateId
              AND NOT COALESCE(sp.removed, false)
            RETURN count(sp) > 0
            """,
    )
    @Transactional(readOnly = true)
    fun existsActiveByPromptTemplateId(promptTemplateId: String): Boolean

    /**
     * Soft-delete all non-removed ScheduledPrompts referencing the given agentConfigId,
     * and soft-delete their linked Prompts in the same query.
     *
     * Also bumps `modified` on each affected ScheduledPrompt and Prompt so that delta-sync
     * clients observe tombstoned entries via [findByScope] with `modifiedSince`.
     *
     * Uses OPTIONAL MATCH for the Prompt so that the ScheduledPrompt is always soft-deleted even when
     * its linked Prompt is already removed or missing (orphaned ScheduledPrompt). The Prompt SET clause
     * only executes when p IS NOT NULL.
     *
     * Returns the number of scheduled prompts soft-deleted.
     */
    @Query(
        $$"""
            MATCH (sp:ScheduledPrompt)
            WHERE sp.agentConfigId = $agentConfigId
              AND NOT COALESCE(sp.removed, false)
            SET sp.removed = true, sp.tripleKey = 'tombstone:' + sp.id, sp.modified = datetime()
            WITH sp
            OPTIONAL MATCH (p:Prompt)
            WHERE p.id = sp.promptTemplateId
              AND NOT COALESCE(p.removed, false)
            WITH sp, p
            WHERE p IS NOT NULL
            SET p.removed = true, p.tripleKey = 'tombstone:' + p.id, p.modified = datetime()
            RETURN count(sp)
            """,
    )
    fun softDeleteWithPromptsByAgentConfigId(agentConfigId: String): Int
}
