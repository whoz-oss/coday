package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [DurableAgentAttemptNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the
 * graph-native atomic statements of the durable-execution protocol. Every
 * mutation returns `count(a)` so the caller knows whether the compare-and-set
 * matched (1) or was fenced / conflicted (0).
 */
interface SpringDataNeo4jDurableAgentAttemptRepository : Neo4jRepository<DurableAgentAttemptNode, String> {

    /**
     * Idempotent register: `MERGE` on the composite id, setting the bridge
     * identity only on create. A re-registration for the same `attemptId`
     * returns the pre-existing node unchanged (no clobbering of a live state).
     */
    @Query(
        """
        MERGE (a:DurableAgentAttempt {id: ${'$'}id})
        ON CREATE SET a.organizationId = ${'$'}organizationId,
                      a.workstreamId = ${'$'}workstreamId,
                      a.namespaceId = ${'$'}namespaceId,
                      a.workflowId = ${'$'}workflowId,
                      a.stepId = ${'$'}stepId,
                      a.attemptId = ${'$'}attemptId,
                      a.caseId = ${'$'}caseId,
                      a.agentName = ${'$'}agentName,
                      a.attemptNumber = ${'$'}attemptNumber,
                      a.capabilityToken = ${'$'}capabilityToken,
                      a.turnCorrelation = ${'$'}turnCorrelation,
                      a.commandId = ${'$'}commandId,
                      a.brief = ${'$'}brief,
                      a.status = 'pending',
                      a.revision = 1,
                      a.createdAt = ${'$'}now,
                      a.updatedAt = ${'$'}now
        RETURN a
        """,
    )
    fun register(
        id: String,
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
        caseId: String,
        agentName: String,
        attemptNumber: Int,
        capabilityToken: String?,
        turnCorrelation: String?,
        commandId: String?,
        brief: String?,
        now: Instant,
    ): DurableAgentAttemptNode

    /**
     * Every non-terminal attempt of the whole graph, oldest revision first.
     * Used by the startup recovery worker to sweep attempts left behind by a
     * crash: the node carries its own tenant identity, so a single scan covers
     * every organization / workstream / namespace.
     */
    @Query(
        """
        MATCH (a:DurableAgentAttempt)
        WHERE NOT a.status IN ['succeeded', 'failed', 'indeterminate', 'interrupted']
        RETURN a
        ORDER BY a.updatedAt ASC
        LIMIT ${'$'}limit
        """,
    )
    fun findNonTerminal(limit: Long): List<DurableAgentAttemptNode>

    /** Locate an attempt by its bridge-supplied `attemptId` inside a workflow. */
    @Query(
        """
        MATCH (a:DurableAgentAttempt)
        WHERE a.organizationId = ${'$'}organizationId
          AND a.workstreamId = ${'$'}workstreamId
          AND a.namespaceId = ${'$'}namespaceId
          AND a.workflowId = ${'$'}workflowId
          AND a.attemptId = ${'$'}attemptId
        RETURN a
        """,
    )
    fun findByAttemptId(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        attemptId: String,
    ): List<DurableAgentAttemptNode>

    /**
     * Explicit business cancellation: a revision-fenced CAS that moves a
     * non-terminal attempt to `interrupted` and rotates the lease owner token so
     * any in-flight worker is fenced out of finalization. Returns 0 when the
     * revision diverged or the attempt is already terminal.
     */
    @Query(
        """
        MATCH (a:DurableAgentAttempt {id: ${'$'}id})
        WHERE a.revision = ${'$'}expectedRevision
          AND NOT a.status IN ['succeeded', 'failed', 'indeterminate', 'interrupted']
        SET a.status = 'interrupted',
            a.ownerToken = ${'$'}ownerToken,
            a.failureCode = ${'$'}failureCode,
            a.completedAt = ${'$'}now,
            a.updatedAt = ${'$'}now,
            a.revision = a.revision + 1
        RETURN count(a) AS cancelled
        """,
    )
    fun cancel(
        id: String,
        expectedRevision: Int,
        ownerToken: String,
        failureCode: String?,
        now: Instant,
    ): Long

    /**
     * Atomic claim (compare-and-set). Exactly one of the branches may match:
     *
     * - fresh claim of a `pending` attempt,
     * - idempotent re-claim by the *same* owner while `claiming`,
     * - preemption of any non-terminal attempt whose lease deadline has passed
     *   (the new owner rotates `ownerToken`, fencing the previous worker out of
     *   finalization).
     *
     * A competing claim by a *different* owner on a live attempt matches
     * nothing and returns 0.
     */
    @Query(
        """
        MATCH (a:DurableAgentAttempt {id: ${'$'}id})
        WHERE a.status = 'pending'
           OR (a.status = 'claiming' AND a.ownerToken = ${'$'}ownerToken)
           OR (a.leaseExpiresAt IS NOT NULL
               AND a.leaseExpiresAt <= ${'$'}now
               AND NOT a.status IN ['succeeded', 'failed', 'indeterminate', 'interrupted'])
        SET a.status = 'claiming',
            a.ownerToken = ${'$'}ownerToken,
            a.leaseExpiresAt = ${'$'}leaseExpiresAt,
            a.startedAt = coalesce(a.startedAt, ${'$'}now),
            a.updatedAt = ${'$'}now,
            a.revision = a.revision + 1
        RETURN count(a) AS claimed
        """,
    )
    fun claim(id: String, ownerToken: String, leaseExpiresAt: Instant?, now: Instant): Long

    /**
     * Owner-guarded intermediate transition (non-terminal -> non-terminal, e.g.
     * `starting`, `running`, `waiting_human`). Returns 0 when the caller is not
     * the current owner or the attempt is already terminal.
     */
    @Query(
        """
        MATCH (a:DurableAgentAttempt {id: ${'$'}id})
        WHERE a.ownerToken = ${'$'}ownerToken
          AND NOT a.status IN ['succeeded', 'failed', 'indeterminate', 'interrupted']
        SET a.status = ${'$'}status,
            a.lastObservedEventId = coalesce(${'$'}lastObservedEventId, a.lastObservedEventId),
            a.updatedAt = ${'$'}now,
            a.revision = a.revision + 1
        RETURN count(a) AS transitioned
        """,
    )
    fun transition(
        id: String,
        ownerToken: String,
        status: String,
        lastObservedEventId: String?,
        now: Instant,
    ): Long

    /**
     * Atomic finalize with lease fencing: the update only lands when the
     * caller's `ownerToken` is still the owner *and* the attempt is not yet
     * terminal. A 0 return means either the token diverged (fencing) or the
     * attempt already reached a terminal status; the caller disambiguates by
     * reading the node back.
     */
    @Query(
        """
        MATCH (a:DurableAgentAttempt {id: ${'$'}id})
        WHERE a.ownerToken = ${'$'}ownerToken
          AND NOT a.status IN ['succeeded', 'failed', 'indeterminate', 'interrupted']
        SET a.status = ${'$'}status,
            a.failureCode = ${'$'}failureCode,
            a.resultEvidenceId = ${'$'}resultEvidenceId,
            a.lastObservedEventId = coalesce(${'$'}lastObservedEventId, a.lastObservedEventId),
            a.completedAt = ${'$'}now,
            a.updatedAt = ${'$'}now,
            a.revision = a.revision + 1
        RETURN count(a) AS finalized
        """,
    )
    fun finalize(
        id: String,
        ownerToken: String,
        status: String,
        failureCode: String?,
        resultEvidenceId: String?,
        lastObservedEventId: String?,
        now: Instant,
    ): Long
}
