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
     * Finds all non-removed Users that are MEMBER or ADMIN of any UserGroup to which
     * [agentConfigId] is DEPLOYED_TO within [namespaceId], then MERGEs a PENDING
     * [ScheduledPromptUserRunNode] for each distinct user.
     *
     * Safe to replay on crash — MERGE is idempotent on the UNIQUE `userRunKey` constraint.
     *
     * Returns the number of UserRuns created (0 when all already existed or no users found).
     */
    @Query(
        $$"""
        MATCH (a:AgentConfig {id: $agentConfigId})-[:DEPLOYED_TO]->(g:UserGroup)
        MATCH (g)-[:BELONGS_TO]->(ns:Namespace {id: $namespaceId})
        MATCH (u:User)-[:MEMBER|ADMIN]->(g)
        WHERE NOT COALESCE(a.removed, false)
          AND NOT COALESCE(g.removed, false)
          AND NOT COALESCE(u.removed, false)
        WITH DISTINCT u.id AS userId
        MERGE (ur:ScheduledPromptUserRun {userRunKey: $runId + '|' + userId})
        ON CREATE SET
            ur.id         = randomUUID(),
            ur.runId      = $runId,
            ur.userId     = userId,
            ur.status     = 'PENDING',
            ur.userRunKey = $runId + '|' + userId,
            ur.version    = 0,
            ur.created    = datetime(),
            ur.modified   = datetime(),
            ur.removed    = null
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

    /** Returns true if at least one UserRun for [runId] has status FAILED. */
    @Query(
        $$"""
        MATCH (ur:ScheduledPromptUserRun)
        WHERE ur.runId = $runId
          AND ur.status = 'FAILED'
          AND NOT COALESCE(ur.removed, false)
        RETURN count(ur) > 0
        """,
    )
    fun hasAnyFailed(runId: String): Boolean
}
