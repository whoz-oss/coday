package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import java.time.Instant

/**
 * Spring Data Neo4j repository for [OutboxEventNode].
 *
 * Besides the CRUD inherited from [Neo4jRepository], it declares the drain
 * queries the former `SELECT ... FOR UPDATE SKIP LOCKED` / `UPDATE` pair
 * provided. The engine is embedded and single-writer, so the claim is a plain
 * oldest-first select: no row lock needs to be taken.
 */
interface SpringDataNeo4jOutboxRepository : Neo4jRepository<OutboxEventNode, String> {

    @Query(
        """
        MATCH (e:OutboxEvent)
        WHERE e.status IN ['pending', 'failed']
          AND e.attempts < 10
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
        RETURN e
        LIMIT 1
        """,
    )
    fun findActiveRunSubmission(id: String, eventType: String): OutboxEventNode?

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
}
