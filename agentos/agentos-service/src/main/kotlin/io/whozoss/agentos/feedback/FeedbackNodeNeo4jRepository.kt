package io.whozoss.agentos.feedback

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [FeedbackNode].
 */
interface FeedbackNodeNeo4jRepository : Neo4jRepository<FeedbackNode, String> {
    /**
     * Find all non-removed feedback nodes for a case, ordered by timestamp.
     *
     * Returns `f, r, e` so SDN maps the [FeedbackNode.caseEvent] @Relationship field.
     */
    @Query(
        $$"""MATCH (f:Feedback)
            WHERE f.caseId = $caseId AND COALESCE(f.removed, false)
            OPTIONAL MATCH (f)-[r:FEEDBACK_ON]->(e:CaseEvent)
            RETURN f, r, e ORDER BY f.timestamp ASC
            """,
    )
    fun findActiveByCaseId(caseId: String): List<FeedbackNode>

    /**
     * Find all non-removed feedback nodes for a specific case event.
     *
     * Returns `f, r, e` so SDN maps the [FeedbackNode.caseEvent] @Relationship field.
     */
    @Query(
        $$"""MATCH (f:Feedback)
            WHERE f.caseEventId = $caseEventId AND COALESCE(f.removed, false)
            OPTIONAL MATCH (f)-[r:FEEDBACK_ON]->(e:CaseEvent)
            RETURN f, r, e ORDER BY f.timestamp ASC
            """,
    )
    fun findActiveByCaseEventId(caseEventId: String): List<FeedbackNode>

    /**
     * Find a single non-removed feedback node for a specific (user, caseEvent) pair.
     *
     * Used by the upsert path: one feedback per user per event.
     * [userId] matches [FeedbackNode.createdBy] which is populated by Spring Data auditing.
     *
     * Returns `f, r, e` (consistent with [findActiveByCaseId] and [findActiveByCaseEventId])
     * so SDN populates the [FeedbackNode.caseEvent] @Relationship field on the result.
     */
    @Query(
        $$"""MATCH (f:Feedback)
            WHERE f.caseEventId = $caseEventId
              AND f.createdBy = $userId
              AND COALESCE(f.removed, false)
            OPTIONAL MATCH (f)-[r:FEEDBACK_ON]->(e:CaseEvent)
            RETURN f, r, e LIMIT 1
            """,
    )
    fun findActiveByUserAndCaseEventId(
        caseEventId: String,
        userId: String,
    ): FeedbackNode?
}
