package io.whozoss.agentos.scheduledPrompt

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [ScheduledPromptUserRunNode].
 */
interface ScheduledPromptUserRunNodeNeo4jRepository : Neo4jRepository<ScheduledPromptUserRunNode, String> {

    /**
     * Traverses the deployment graph and materialises PENDING UserRuns in a single query.
     *
     * Resolves all non-removed Users that are **deployment targets** of [agentConfigId]
     * in [namespaceId] via two paths:
     *
     * 1. **UserGroup deployment**: `AgentConfig -[:DEPLOYED_TO]-> UserGroup <-[:MEMBER|ADMIN]- User`,
     *    where the UserGroup `[:BELONGS_TO]` the namespace.
     * 2. **Namespace deployment**: `AgentConfig -[:DEPLOYED_TO]-> Namespace <-[:MEMBER|ADMIN]- User`.
     *
     * Super-admins are intentionally **excluded** unless they are also members of a
     * deployed UserGroup or namespace. `isAdmin` grants the ability to *use* any agent
     * (access control), but scheduled prompts target users who are *deployed to* the
     * agent — being an admin is not the same as being a target audience.
     *
     * Both paths are resolved via a `CALL { UNION }` subquery that traverses the graph
     * starting from the agent — no full User scan. `UNION` deduplicates users found via
     * both paths. Then MERGEs a PENDING [ScheduledPromptUserRunNode] for each distinct eligible user.
     *
     * Safe to replay on crash — MERGE is idempotent on the composite UNIQUE `(runId, userId)` constraint.
     *
     * Returns the number of UserRuns created (0 when all already existed or no users found).
     */
    @Query(
        $$"""
        MATCH (a:AgentConfig {id: $agentConfigId})
          WHERE NOT COALESCE(a.removed, false) AND a.enabled = true
        MATCH (ns:Namespace {id: $namespaceId})
          WHERE NOT COALESCE(ns.removed, false)
        CALL {
            WITH a, ns
            MATCH (a)-[:DEPLOYED_TO]->(g:UserGroup)-[:BELONGS_TO]->(ns)
              WHERE NOT COALESCE(g.removed, false)
            MATCH (u:User)-[:MEMBER|ADMIN]->(g)
              WHERE NOT COALESCE(u.removed, false)
            RETURN u.id AS userId
            UNION
            WITH a, ns
            MATCH (a)-[:DEPLOYED_TO]->(ns)
            MATCH (u:User)-[:MEMBER|ADMIN]->(ns)
              WHERE NOT COALESCE(u.removed, false)
            RETURN u.id AS userId
        }
        WITH DISTINCT userId
        MERGE (ur:ScheduledPromptUserRun {runId: $runId, userId: userId})
        ON CREATE SET
            ur.id       = randomUUID(),
            ur.status   = 'PENDING',
            ur.version  = 0,
            ur.created  = datetime(),
            ur.modified = datetime(),
            ur.removed  = null
        RETURN count(ur)
        """,
    )
    fun materialize(runId: String, agentConfigId: String, namespaceId: String): Int

    /**
     * Read-only query returning up to [limit] claimable UserRuns, ordered oldest-first.
     *
     * "Claimable" means:
     * - status = 'PENDING', OR
     * - status = 'RUNNING' AND leaseUntil < now (expired lease — crash recovery).
     *
     * The caller (Neo4jScheduledPromptUserRunRepository.claimBatch) transitions each node
     * to RUNNING via SDN `save()`, which enforces the `@Version` optimistic lock and
     * prevents two instances from claiming the same entry.
     */
    @Query(
        $$"""
        MATCH (ur:ScheduledPromptUserRun)
        WHERE (ur.status = 'PENDING' OR (ur.status = 'RUNNING' AND ur.leaseUntil < $now))
          AND NOT COALESCE(ur.removed, false)
        RETURN ur ORDER BY ur.created ASC LIMIT $limit
        """,
    )
    fun findClaimable(now: Instant, limit: Int): List<ScheduledPromptUserRunNode>

    /** All UserRuns for a given Run, ordered by creation time. */
    @Query(
        $$"""
        MATCH (ur:ScheduledPromptUserRun)
        WHERE ur.runId = $runId
          AND NOT COALESCE(ur.removed, false)
        RETURN ur ORDER BY ur.created ASC
        """,
    )
    fun findByRunId(runId: String): List<ScheduledPromptUserRunNode>

    /** Count of UserRuns for [runId] in one of the given statuses. */
    @Query(
        $$"""
        MATCH (ur:ScheduledPromptUserRun)
        WHERE ur.runId = $runId
          AND ur.status IN $statuses
          AND NOT COALESCE(ur.removed, false)
        RETURN count(ur)
        """,
    )
    fun countByRunIdAndStatuses(runId: String, statuses: List<String>): Int

    /**
     * Returns a single UserRun for [runId] that is still active (PENDING or RUNNING),
     * or null if none exists. LIMIT 1 lets Neo4j stop at the first match.
     * The caller maps non-null → true.
     */
    @Query(
        $$"""
        MATCH (ur:ScheduledPromptUserRun)
        WHERE ur.runId = $runId
          AND ur.status IN ['PENDING', 'RUNNING']
          AND NOT COALESCE(ur.removed, false)
        RETURN ur LIMIT 1
        """,
    )
    fun findOneActive(runId: String): ScheduledPromptUserRunNode?

    /**
     * Returns a single FAILED UserRun for [runId], or null if none exists.
     * LIMIT 1 lets Neo4j stop at the first match. The caller maps non-null → true.
     */
    @Query(
        $$"""
        MATCH (ur:ScheduledPromptUserRun)
        WHERE ur.runId = $runId
          AND ur.status = 'FAILED'
          AND NOT COALESCE(ur.removed, false)
        RETURN ur LIMIT 1
        """,
    )
    fun findOneFailed(runId: String): ScheduledPromptUserRunNode?
}
