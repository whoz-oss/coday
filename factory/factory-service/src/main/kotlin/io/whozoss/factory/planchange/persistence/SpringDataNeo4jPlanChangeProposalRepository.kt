package io.whozoss.factory.planchange.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [PlanChangeProposalNode].
 *
 * Every query is constrained by the tenant scope properties
 * `(organizationId, workstreamId)`; the composite node id remains the uniqueness
 * guarantee. The idempotency lookup follows the prompt's tuple
 * `(organizationId, workstreamId, workflowId, idempotencyKey)`.
 */
interface SpringDataNeo4jPlanChangeProposalRepository : Neo4jRepository<PlanChangeProposalNode, String> {

    /** Every proposal of the workflow in the tenant scope, oldest first. */
    @Query(
        """
        MATCH (p:PlanChangeProposal)
        WHERE p.organizationId = ${'$'}organizationId
          AND p.workstreamId = ${'$'}workstreamId
          AND p.namespaceId = ${'$'}namespaceId
          AND p.workflowId = ${'$'}workflowId
        RETURN p
        ORDER BY p.createdAt ASC, p.proposalId ASC
        """,
    )
    fun findByWorkflow(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
    ): List<PlanChangeProposalNode>

    /** Every proposal of the workflow with the given derived current status, oldest first. */
    @Query(
        """
        MATCH (p:PlanChangeProposal)
        WHERE p.organizationId = ${'$'}organizationId
          AND p.workstreamId = ${'$'}workstreamId
          AND p.namespaceId = ${'$'}namespaceId
          AND p.workflowId = ${'$'}workflowId
          AND p.currentStatus = ${'$'}status
        RETURN p
        ORDER BY p.createdAt ASC, p.proposalId ASC
        """,
    )
    fun findByWorkflowAndStatus(
        organizationId: String,
        workstreamId: String,
        namespaceId: String,
        workflowId: String,
        status: String,
    ): List<PlanChangeProposalNode>

    /**
     * The proposal persisted under the idempotency tuple
     * `(organizationId, workstreamId, workflowId, idempotencyKey)`, or `null`.
     */
    @Query(
        """
        MATCH (p:PlanChangeProposal)
        WHERE p.organizationId = ${'$'}organizationId
          AND p.workstreamId = ${'$'}workstreamId
          AND p.workflowId = ${'$'}workflowId
          AND p.idempotencyKey = ${'$'}idempotencyKey
        RETURN p
        ORDER BY p.createdAt ASC
        LIMIT 1
        """,
    )
    fun findByIdempotency(
        organizationId: String,
        workstreamId: String,
        workflowId: String,
        idempotencyKey: String,
    ): PlanChangeProposalNode?
}
