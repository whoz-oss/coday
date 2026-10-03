package io.whozoss.factory.planchange.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [PlanChangeDecisionNode] (the append-only
 * decision log of the governed replanning aggregate).
 *
 * [maxSequence] implements the graph-native sequence generation —
 * `MAX(sequence) + 1` — mirroring the attempt journal's
 * [io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jDurableAgentAttemptJournalRepository.maxSequence].
 */
interface SpringDataNeo4jPlanChangeDecisionRepository : Neo4jRepository<PlanChangeDecisionNode, String> {

    /** The highest decision sequence of the proposal, or 0 when it has no entry yet. */
    @Query(
        """
        MATCH (d:PlanChangeDecision)
        WHERE d.organizationId = ${'$'}organizationId
          AND d.workstreamId = ${'$'}workstreamId
          AND d.namespaceId = ${'$'}namespaceId
          AND d.workflowId = ${'$'}workflowId
          AND d.proposalId = ${'$'}proposalId
        RETURN coalesce(max(d.sequence), 0) AS maxSequence
        """,
    )
    fun maxSequence(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): Long

    /** The whole decision log of the proposal, oldest entry first. */
    @Query(
        """
        MATCH (d:PlanChangeDecision)
        WHERE d.organizationId = ${'$'}organizationId
          AND d.workstreamId = ${'$'}workstreamId
          AND d.namespaceId = ${'$'}namespaceId
          AND d.workflowId = ${'$'}workflowId
          AND d.proposalId = ${'$'}proposalId
        RETURN d
        ORDER BY d.sequence ASC
        """,
    )
    fun findByProposal(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): List<PlanChangeDecisionNode>
}
