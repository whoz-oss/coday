package io.whozoss.factory.planchange.persistence

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of the append-only plan-change proposal aggregate
 * (Phase 8 governed replanning).
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, proposalId)` encoded as
 * a single string (see [compositeId]), so a scope-less access is impossible by
 * construction. The submitted payload is immutable: [reasonCode], [summary], the
 * proposed changes and the evidence references are written once and never updated.
 * Only the derived decision cache ([currentStatus], [revision], [updatedAt]) is
 * maintained, its source of truth being the append-only `PlanChangeDecision` log.
 *
 * The complex object lists are stored as serialized JSON ([dependencyChangesJson],
 * [scopeChangesJson]) like `OracleExecutionNode.payload`, while the simple string
 * lists stay native Neo4j list properties. [idempotencyKey] is a denormalized
 * indexed property for the idempotent replay lookup and [requestHash] the canonical
 * payload hash used to detect idempotency-key collisions.
 */
@Node("PlanChangeProposal")
data class PlanChangeProposalNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val proposalId: String,
    val expectedRevision: Int,
    val reasonCode: String,
    val summary: String,
    val proposalType: String,
    val affectedStepIds: List<String> = emptyList(),
    val dependencyChangesJson: String = "[]",
    val scopeChangesJson: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val idempotencyKey: String,
    /** Canonical SHA-256 hash of the normalized submit payload (collision detection). */
    val requestHash: String,
    val kind: String,
    val recommendedVerdict: String,
    val currentStatus: String,
    val revision: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        /** Encodes the composite business key into a single node id. */
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            proposalId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$proposalId"
    }
}
