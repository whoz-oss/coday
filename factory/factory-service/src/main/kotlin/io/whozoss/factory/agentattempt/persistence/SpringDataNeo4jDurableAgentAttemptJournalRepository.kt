package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [DurableAgentAttemptJournalNode] (the
 * append-only attempt-transition journal).
 *
 * [maxSequence] implements the graph-native sequence generation —
 * `MAX(sequence) + 1` — mirroring the delivery journal's
 * [io.whozoss.factory.delivery.persistence.SpringDataNeo4jDeliveryRecordRepository.maxRecordSequence].
 */
interface SpringDataNeo4jDurableAgentAttemptJournalRepository :
    Neo4jRepository<DurableAgentAttemptJournalNode, String> {

    /** The highest journal sequence of the attempt, or 0 when it has no entry yet. */
    @Query(
        """
        MATCH (j:DurableAgentAttemptJournal)
        WHERE j.organizationId = ${'$'}organizationId
          AND j.workstreamId = ${'$'}workstreamId
          AND j.namespaceId = ${'$'}namespaceId
          AND j.workflowId = ${'$'}workflowId
          AND j.stepId = ${'$'}stepId
          AND j.attemptId = ${'$'}attemptId
        RETURN coalesce(max(j.sequence), 0) AS maxSequence
        """,
    )
    fun maxSequence(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): Long

    /** The whole journal of the attempt, oldest entry first. */
    @Query(
        """
        MATCH (j:DurableAgentAttemptJournal)
        WHERE j.organizationId = ${'$'}organizationId
          AND j.workstreamId = ${'$'}workstreamId
          AND j.namespaceId = ${'$'}namespaceId
          AND j.workflowId = ${'$'}workflowId
          AND j.stepId = ${'$'}stepId
          AND j.attemptId = ${'$'}attemptId
        RETURN j
        ORDER BY j.sequence ASC
        """,
    )
    fun findByAttempt(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        attemptId: String,
    ): List<DurableAgentAttemptJournalNode>
}
