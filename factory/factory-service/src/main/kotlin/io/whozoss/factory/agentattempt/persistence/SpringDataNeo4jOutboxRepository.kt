package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [OutboxEventNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the drain
 * queries the former `SELECT ... FOR UPDATE SKIP LOCKED` / `UPDATE` pair
 * provided. Sources use a short transactional handoff; continuation claims and
 * token-fenced transitions use a dependent write to lock before checking CAS.
 */
interface SpringDataNeo4jOutboxRepository : Neo4jRepository<OutboxEventNode, String> {

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE (e.status IN ['pending', 'failed'] AND e.attempts < 10
                 AND e.eventType <> 'session_continuation')
           OR (e.eventType = 'session_continuation' AND e.status IN ['pending', 'claimed'])
        RETURN DISTINCT e.organizationId AS organizationId
        """,
    )
    fun findPendingOrganizations(): List<String>

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.status IN ['pending', 'failed']
          AND e.attempts < 10
          AND e.eventType <> 'session_continuation'
        RETURN e
        ORDER BY e.createdAt ASC
        LIMIT toInteger(${'$'}limit)
        """,
    )
    fun findPending(organizationId: String, limit: Int): List<OutboxEventNode>

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE e.organizationId = ${'$'}organizationId
        RETURN e
        ORDER BY e.createdAt ASC
        """,
    )
    fun findAllByOrganization(organizationId: String): List<OutboxEventNode>

    /** Pending/dispatched submission fencing duplicate workflow starts. */
    @Query(
        """
        MATCH (e:OutboxEvent {id: ${'$'}id})
        WHERE e.eventType = ${'$'}eventType
          AND e.status IN ['pending', 'dispatched']
        OPTIONAL MATCH (c:OutboxEvent {id: ${'$'}continuationId})
        WITH e, c WHERE e.status = 'pending' OR c IS NULL OR c.status <> 'dead'
        RETURN e
        LIMIT 1
        """,
    )
    fun findActiveRunSubmission(id: String, eventType: String, continuationId: String): OutboxEventNode?

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE e.organizationId = ${'$'}organizationId AND e.eventId = ${'$'}eventId
        SET e.status = 'dispatched', e.dispatchedAt = ${'$'}dispatchedAt
        RETURN count(e) AS updated
        """,
    )
    fun markDispatched(organizationId: String, eventId: String, dispatchedAt: Instant): Long

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE e.organizationId = ${'$'}organizationId AND e.eventId = ${'$'}eventId
        SET e.status = 'failed', e.attempts = e.attempts + 1
        RETURN count(e) AS updated
        """,
    )
    fun markFailed(organizationId: String, eventId: String): Long

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.eventType = 'session_continuation'
          AND ((e.status = 'pending' AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= ${'$'}now))
               OR (e.status = 'claimed' AND e.leaseUntil <= ${'$'}now))
        RETURN e ORDER BY e.createdAt ASC LIMIT toInteger(${'$'}limit)
        """,
    )
    fun findDueContinuations(organizationId: String, now: Instant, limit: Int): List<OutboxEventNode>

    @Query("""
        MATCH (e:OutboxEvent {id: ${'$'}id}), (c:OutboxEvent {id: ${'$'}continuationId})
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e, c WHERE c.status = 'dead' AND e.status IN ['dispatched', 'failed']
        SET e.status = 'pending', e.attempts = 0, e.payload = ${'$'}payload
        REMOVE e.dispatchedAt, c.lastWakeSource
        RETURN count(e)
    """)
    fun resubmitAfterDead(id: String, continuationId: String, payload: String): Long

    @Query("""
        MATCH (e:OutboxEvent {id: ${'$'}id})
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e WHERE e.status = 'pending' AND e.retryDeadline IS NULL
        SET e.retryDeadline = ${'$'}deadline, e.nextAttemptAt = ${'$'}next,
            e.diagnostic = 'WORKSPACE_ADOPTION_DEFERRED'
        RETURN count(e)
    """)
    fun beginAdoptionWait(id: String, deadline: Instant, next: Instant): Long

    // The dependent SET obtains Neo4j's write lock BEFORE checking the CAS predicate.
    @Query("""
        MATCH (e:OutboxEvent {id: ${'$'}id})
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e WHERE (e.status = 'pending' AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= ${'$'}now))
            OR (e.status = 'claimed' AND e.leaseUntil <= ${'$'}now)
        SET e.status = 'claimed', e.claimToken = ${'$'}token, e.leaseUntil = ${'$'}leaseUntil
        RETURN e
    """)
    fun claimContinuation(id: String, token: String, now: Instant, leaseUntil: Instant): OutboxEventNode?

    @Query("""
        MATCH (e:OutboxEvent {id: ${'$'}id})
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e WHERE e.status = 'claimed' AND e.claimToken = ${'$'}token AND e.leaseUntil > ${'$'}now
        SET e.status = CASE WHEN e.wakeRequested = true THEN 'pending' ELSE 'dispatched' END,
            e.dispatchedAt = CASE WHEN e.wakeRequested = true THEN null ELSE ${'$'}now END,
            e.payload = CASE WHEN e.wakeRequested = true THEN coalesce(e.wakePayload, e.payload) ELSE e.payload END,
            e.attempts = CASE WHEN e.wakeRequested = true THEN 0 ELSE e.attempts END,
            e.retryDeadline = CASE WHEN e.wakeRequested = true THEN null ELSE e.retryDeadline END,
            e.nextAttemptAt = CASE WHEN e.wakeRequested = true THEN e.wakeNotBefore ELSE e.nextAttemptAt END,
            e.diagnostic = CASE WHEN e.wakeRequested = true THEN null ELSE e.diagnostic END
        REMOVE e.wakeRequested, e.wakePayload, e.wakeNotBefore, e.claimToken, e.leaseUntil
        RETURN count(e)
    """)
    fun acknowledgeContinuation(id: String, token: String, now: Instant): Long

    @Query("""
        MATCH (e:OutboxEvent {id: ${'$'}id})
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e WHERE e.status = 'claimed' AND e.claimToken = ${'$'}token AND e.leaseUntil > ${'$'}now
        WITH e, coalesce(e.retryDeadline, ${'$'}deadline) AS deadline
        WITH e, deadline, ${'$'}transient AND ${'$'}next < deadline AS retry, coalesce(e.wakeRequested, false) AS wake
        SET e.status = CASE WHEN wake OR retry THEN 'pending' ELSE 'dead' END,
            e.payload = CASE WHEN wake THEN coalesce(e.wakePayload, e.payload) ELSE e.payload END,
            e.retryDeadline = CASE WHEN wake THEN null WHEN ${'$'}transient THEN deadline ELSE e.retryDeadline END,
            e.nextAttemptAt = CASE WHEN wake THEN e.wakeNotBefore WHEN retry THEN ${'$'}next ELSE null END,
            e.attempts = CASE WHEN wake THEN 0 ELSE e.attempts + 1 END,
            e.diagnostic = CASE WHEN wake THEN null WHEN retry THEN 'WORKSPACE_ADOPTION_DEFERRED'
                WHEN ${'$'}transient THEN 'WORKSPACE_ADOPTION_RETRY_EXHAUSTED' ELSE 'CONTINUATION_FAILED' END
        REMOVE e.wakeRequested, e.wakePayload, e.wakeNotBefore, e.claimToken, e.leaseUntil
        RETURN count(e)
    """)
    fun deferClaimedContinuation(id: String, token: String, now: Instant, next: Instant, deadline: Instant, transient: Boolean): Long

    @Query("""
        MERGE (e:OutboxEvent {id: ${'$'}id})
        ON CREATE SET e.organizationId = ${'$'}organizationId, e.eventId = ${'$'}eventId,
            e.workstreamId = ${'$'}workstreamId, e.eventType = 'session_continuation',
            e.status = 'dispatched', e.createdAt = ${'$'}now, e.attempts = 0
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e, ${'$'}wake AND (e.lastWakeSource IS NULL OR e.lastWakeSource <> ${'$'}sourceId) AS fresh
        SET e.wakeRequested = CASE WHEN e.status = 'claimed' AND fresh THEN true ELSE e.wakeRequested END,
            e.wakePayload = CASE WHEN e.status = 'claimed' AND fresh THEN ${'$'}payload ELSE e.wakePayload END,
            e.wakeNotBefore = CASE WHEN e.status = 'claimed' AND fresh THEN null ELSE e.wakeNotBefore END,
            e.lastWakeSource = CASE WHEN fresh THEN ${'$'}sourceId ELSE e.lastWakeSource END
        WITH e, fresh WHERE (e.status IN ['dead', 'dispatched'] AND (fresh OR NOT ${'$'}wake))
            OR (e.status = 'pending' AND fresh)
        SET e.status = 'pending', e.payload = ${'$'}payload, e.attempts = 0,
            e.retryDeadline = ${'$'}deadline, e.nextAttemptAt = ${'$'}next,
            e.diagnostic = CASE WHEN ${'$'}deadline IS NULL THEN null ELSE 'WORKSPACE_ADOPTION_DEFERRED' END
        REMOVE e.wakeRequested, e.wakePayload, e.wakeNotBefore, e.claimToken, e.leaseUntil, e.dispatchedAt
        RETURN count(e)
    """)
    fun enqueueContinuation(id: String, organizationId: String, eventId: String, workstreamId: String,
        payload: String, now: Instant, deadline: Instant?, next: Instant?, wake: Boolean, sourceId: String?): Long

    /**
     * Requests a DELAYED wake of the continuation (external polling): the continuation becomes due again
     * at [notBefore] instead of immediately.
     *  - claimed: the wake is remembered and applied on acknowledgement, with `nextAttemptAt = notBefore`.
     *    An immediate wake already requested always wins over a delayed one, and the earliest delay wins;
     *  - pending: `nextAttemptAt` is lowered to [notBefore] when later; an adoption backoff (a retry
     *    deadline is set) and an immediately-due row are never touched;
     *  - dispatched / dead: re-pended with `nextAttemptAt = notBefore`.
     * The previous values are captured first: Cypher SET items must not depend on each other.
     */
    @Query("""
        MERGE (e:OutboxEvent {id: ${'$'}id})
        ON CREATE SET e.organizationId = ${'$'}organizationId, e.eventId = ${'$'}eventId,
            e.workstreamId = ${'$'}workstreamId, e.eventType = 'session_continuation',
            e.status = 'dispatched', e.createdAt = ${'$'}now, e.attempts = 0
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e, e.status AS st, coalesce(e.wakeRequested, false) AS hadWake, e.wakeNotBefore AS previousNotBefore,
            e.nextAttemptAt AS previousNext, e.retryDeadline AS previousDeadline
        SET e.wakeRequested = CASE WHEN st = 'claimed' THEN true ELSE e.wakeRequested END,
            e.wakePayload = CASE WHEN st = 'claimed' THEN coalesce(e.wakePayload, ${'$'}payload) ELSE e.wakePayload END,
            e.wakeNotBefore = CASE WHEN st <> 'claimed' THEN e.wakeNotBefore
                WHEN hadWake AND previousNotBefore IS NULL THEN null
                WHEN previousNotBefore IS NULL OR previousNotBefore > ${'$'}notBefore THEN ${'$'}notBefore
                ELSE previousNotBefore END,
            e.status = CASE WHEN st IN ['dispatched', 'dead'] THEN 'pending' ELSE e.status END,
            e.payload = CASE WHEN st IN ['dispatched', 'dead'] THEN ${'$'}payload ELSE e.payload END,
            e.attempts = CASE WHEN st IN ['dispatched', 'dead'] THEN 0 ELSE e.attempts END,
            e.retryDeadline = CASE WHEN st IN ['dispatched', 'dead'] THEN null ELSE previousDeadline END,
            e.dispatchedAt = CASE WHEN st IN ['dispatched', 'dead'] THEN null ELSE e.dispatchedAt END,
            e.diagnostic = CASE WHEN st IN ['dispatched', 'dead'] THEN 'EXTERNAL_POLL_SCHEDULED' ELSE e.diagnostic END,
            e.nextAttemptAt = CASE WHEN st IN ['dispatched', 'dead'] THEN ${'$'}notBefore
                WHEN st = 'pending' AND previousDeadline IS NULL AND previousNext IS NOT NULL AND previousNext > ${'$'}notBefore
                    THEN ${'$'}notBefore
                ELSE previousNext END
        RETURN count(e)
    """)
    fun requestDelayedWake(id: String, organizationId: String, eventId: String, workstreamId: String,
        payload: String, now: Instant, notBefore: Instant): Long

    @Query("""
        MATCH (e:OutboxEvent {id: ${'$'}id})
        SET e.casVersion = coalesce(e.casVersion, 0) + 1
        WITH e WHERE e.status = 'claimed' AND e.claimToken = ${'$'}token
        SET e.status = 'pending',
            e.payload = coalesce(e.wakePayload, e.payload),
            e.attempts = CASE WHEN e.wakeRequested = true THEN 0 ELSE e.attempts END,
            e.retryDeadline = CASE WHEN e.wakeRequested = true THEN null ELSE e.retryDeadline END,
            e.nextAttemptAt = CASE WHEN e.wakeRequested = true THEN e.wakeNotBefore ELSE e.nextAttemptAt END,
            e.diagnostic = CASE WHEN e.wakeRequested = true THEN null ELSE e.diagnostic END
        REMOVE e.wakeRequested, e.wakePayload, e.wakeNotBefore, e.claimToken, e.leaseUntil
        RETURN count(e)
    """)
    fun releaseContinuation(id: String, token: String): Long

    @Query("""
        MATCH (e:OutboxEvent {organizationId: ${'$'}organizationId, workstreamId: ${'$'}workstreamId})
        WHERE e.eventType = 'session_run_requested'
        RETURN e ORDER BY e.createdAt DESC
    """)
    fun findSubmissionContexts(organizationId: String, workstreamId: String): List<OutboxEventNode>
}
