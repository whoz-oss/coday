package io.whozoss.factory.planchange.persistence

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.planchange.domain.PlanChangeDecision
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeProposal
import io.whozoss.factory.planchange.domain.toDomain
import io.whozoss.factory.planchange.domain.toNode
import io.whozoss.factory.planchange.domain.planChangeProposalNotFound
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of the tenant-scoped plan-change proposal store (Phase 8
 * governed replanning).
 *
 * Every statement is constrained to the caller's [TenantScope]
 * `(organizationId, workstreamId)`; the composite node ids make a scope-less
 * access impossible by construction.
 *
 * Append-only model: [create] writes the immutable proposal once;
 * [appendDecision] appends an immutable `PlanChangeDecision` event with the
 * graph-native `MAX(sequence) + 1` sequence and — the source of truth being the
 * decision log — only refreshes the proposal's derived cache
 * (`currentStatus` / `revision` / `updatedAt`). The submitted payload and the
 * evidence references are never updated nor deleted.
 */
@Repository
@Primary
class Neo4jPlanChangeProposalRepository(
    private val proposals: SpringDataNeo4jPlanChangeProposalRepository,
    private val decisions: SpringDataNeo4jPlanChangeDecisionRepository,
) {

    /** Persist a new immutable proposal (revision 1, `PENDING_VALIDATION`). */
    fun create(scope: TenantScope, proposal: PlanChangeProposal, requestHash: String): PlanChangeProposal {
        val scoped = proposal.copy(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
        )
        return proposals.save(scoped.toNode(requestHash)).toDomain()
    }

    /** The proposal of [proposalId] in [scope], or `null` when absent. */
    fun findById(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): PlanChangeProposal? = findNode(scope, namespaceId, workflowId, proposalId)?.toDomain()

    /** The proposals of [workflowId] in [scope], optionally filtered by their derived current status. */
    fun findByWorkflow(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        status: PlanChangeDecisionStatus? = null,
    ): List<PlanChangeProposal> {
        val nodes = if (status == null) {
            proposals.findByWorkflow(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
        } else {
            proposals.findByWorkflowAndStatus(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                status.dbValue,
            )
        }
        return nodes.map { it.toDomain() }
    }

    /**
     * The persisted proposal node of the idempotency tuple
     * `(organizationId, workstreamId, workflowId, idempotencyKey)`, or `null`.
     * Exposed at the node level so the service can compare the stored canonical
     * [PlanChangeProposalNode.requestHash] before deciding replay vs collision.
     */
    fun findIdempotentNode(
        scope: TenantScope,
        workflowId: String,
        idempotencyKey: String,
    ): PlanChangeProposalNode? =
        proposals
            .findByIdempotency(scope.organizationId, scope.workstreamId, workflowId, idempotencyKey)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }

    /**
     * Append one immutable decision event to the proposal's log and return the
     * refreshed proposal plus the recorded event.
     *
     * The event sequence is generated as `MAX(sequence) + 1` (embedded engine is
     * single-writer). When [bumpRevision] is true the proposal's derived cache is
     * refreshed (`currentStatus`, `revision + 1`, `updatedAt`); the initial
     * submission event passes `false` so a freshly created proposal stays at
     * revision 1. The decision log itself is never updated nor deleted.
     *
     * @throws FactoryHttpException 404 `PLAN_CHANGE_PROPOSAL_NOT_FOUND` when the
     * proposal is absent in [scope].
     */
    fun appendDecision(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
        status: PlanChangeDecisionStatus,
        actorId: String,
        reason: String? = null,
        idempotencyKey: String? = null,
        bumpRevision: Boolean = true,
    ): Pair<PlanChangeProposal, PlanChangeDecision> {
        val existing = findNode(scope, namespaceId, workflowId, proposalId)
            ?: planChangeProposalNotFound(proposalId)
        val sequence = decisions.maxSequence(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            proposalId,
        ) + 1
        val recordedAt = Instant.now()
        val decisionNode = decisions.save(
            PlanChangeDecisionNode(
                id = PlanChangeDecisionNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    proposalId,
                    sequence,
                ),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = namespaceId,
                workflowId = workflowId,
                proposalId = proposalId,
                sequence = sequence,
                status = status.dbValue,
                actorId = actorId,
                reason = reason,
                idempotencyKey = idempotencyKey,
                recordedAt = recordedAt,
            ),
        )
        val refreshed = if (bumpRevision) {
            proposals.save(
                existing.copy(
                    currentStatus = status.dbValue,
                    revision = existing.revision + 1,
                    createdAt = existing.createdAt,
                    updatedAt = recordedAt,
                ),
            )
        } else {
            existing
        }
        return refreshed.toDomain() to decisionNode.toDomain()
    }

    /** The whole decision log of the proposal, oldest event first. */
    fun listDecisions(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): List<PlanChangeDecision> =
        decisions
            .findByProposal(scope.organizationId, scope.workstreamId, namespaceId, workflowId, proposalId)
            .map { it.toDomain() }

    /** The latest decision event of the proposal, or `null` when it has none. */
    fun latestDecision(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): PlanChangeDecision? = listDecisions(scope, namespaceId, workflowId, proposalId).lastOrNull()

    private fun findNode(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): PlanChangeProposalNode? =
        proposals
            .findById(
                PlanChangeProposalNode.compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    namespaceId,
                    workflowId,
                    proposalId,
                ),
            )
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }

    private fun PlanChangeDecisionNode.toDomain(): PlanChangeDecision =
        PlanChangeDecision(
            sequence = sequence,
            status = PlanChangeDecisionStatus.fromDbValue(status),
            actorId = actorId,
            reason = reason,
            idempotencyKey = idempotencyKey,
            recordedAt = recordedAt,
        )
}
