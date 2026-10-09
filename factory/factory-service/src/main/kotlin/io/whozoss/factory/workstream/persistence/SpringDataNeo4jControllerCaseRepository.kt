package io.whozoss.factory.workstream.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [ControllerCaseExecutionNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the
 * graph-native statements of the controller case lifecycle: the idempotent
 * `MERGE ... ON CREATE SET` start, the archive compare-and-set (returning
 * `count(c)` so the caller knows whether an active case was matched), and the
 * tenant-scoped history reads.
 */
interface SpringDataNeo4jControllerCaseRepository : Neo4jRepository<ControllerCaseExecutionNode, String> {

    /**
     * The active controller case of a workstream, or `null` when none. The
     * single-active invariant is enforced by the write paths; the defensive
     * `ORDER BY ... LIMIT 1` deterministically picks the highest sequence if
     * the invariant were ever violated.
     */
    @Query(
        """
        MATCH (c:ControllerCaseExecution)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
          AND c.status = 'active'
        RETURN c
        ORDER BY c.sequence DESC
        LIMIT 1
        """,
    )
    fun findActiveByWorkstream(organizationId: String, workstreamId: String): ControllerCaseExecutionNode?

    /** Every controller case execution of a workstream, oldest first. */
    @Query(
        """
        MATCH (c:ControllerCaseExecution)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
        RETURN c
        ORDER BY c.sequence ASC
        """,
    )
    fun findAllByWorkstream(organizationId: String, workstreamId: String): List<ControllerCaseExecutionNode>

    /** The highest `sequence` recorded for a workstream, or `null` when none. */
    @Query(
        """
        MATCH (c:ControllerCaseExecution)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
        RETURN max(c.sequence) AS maxSequence
        """,
    )
    fun maxSequence(organizationId: String, workstreamId: String): Long?

    /**
     * Idempotent start: `MERGE` on the composite id, setting every field only
     * on create. Re-starting an already-known `caseId` returns the
     * pre-existing node unchanged (no clobbering of a live state).
     */
    @Query(
        """
        MERGE (c:ControllerCaseExecution {id: ${'$'}id})
        ON CREATE SET c.organizationId = ${'$'}organizationId,
                      c.workstreamId = ${'$'}workstreamId,
                      c.caseId = ${'$'}caseId,
                      c.controllerAgentRef = ${'$'}controllerAgentRef,
                      c.status = 'active',
                      c.sequence = ${'$'}sequence,
                      c.startedAt = ${'$'}startedAt,
                      c.compactionReason = ${'$'}compactionReason,
                      c.contextSummary = ${'$'}contextSummary,
                      c.contextRevision = ${'$'}contextRevision,
                      c.createdAt = ${'$'}now,
                      c.updatedAt = ${'$'}now
        RETURN c
        """,
    )
    fun mergeStart(
        id: String,
        organizationId: String,
        workstreamId: String,
        caseId: String,
        controllerAgentRef: String,
        sequence: Int,
        startedAt: Instant,
        compactionReason: String?,
        contextSummary: String?,
        contextRevision: String?,
        now: Instant,
    ): ControllerCaseExecutionNode

    /**
     * Archive compare-and-set: moves the current `active` case of a
     * workstream to `archived`, stamping the archival timestamp and the
     * compaction reason (why *this* case ended / why the next case was
     * created). Returns `count(c)` — 1 when an active case was archived, 0
     * when none was active.
     */
    @Query(
        """
        MATCH (c:ControllerCaseExecution)
        WHERE c.organizationId = ${'$'}organizationId
          AND c.workstreamId = ${'$'}workstreamId
          AND c.status = 'active'
        SET c.status = 'archived',
            c.archivedAt = ${'$'}archivedAt,
            c.compactionReason = coalesce(c.compactionReason, ${'$'}compactionReason),
            c.updatedAt = ${'$'}archivedAt
        RETURN count(c) AS archived
        """,
    )
    fun archiveActive(
        organizationId: String,
        workstreamId: String,
        archivedAt: Instant,
        compactionReason: String?,
    ): Long
}
