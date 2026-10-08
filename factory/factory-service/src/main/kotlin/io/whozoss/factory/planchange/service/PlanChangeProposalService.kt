package io.whozoss.factory.planchange.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.planchange.domain.GovernanceGateEvaluator
import io.whozoss.factory.planchange.domain.PlanChangeClassifier
import io.whozoss.factory.planchange.domain.PlanChangeDecideCommand
import io.whozoss.factory.planchange.domain.PlanChangeDecision
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeProposal
import io.whozoss.factory.planchange.domain.PlanChangeSubmitCommand
import io.whozoss.factory.planchange.domain.PlanChangeValidation
import io.whozoss.factory.planchange.domain.planChangeIdempotencyCollision
import io.whozoss.factory.planchange.domain.planChangeProposalNotFound
import io.whozoss.factory.planchange.domain.toDomain
import io.whozoss.factory.planchange.persistence.Neo4jPlanChangeProposalRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Result of a submit or decide call: the (refreshed) proposal, its full decision
 * log and whether the call was an idempotent replay of an already persisted record.
 */
data class PlanChangeProposalResult(
    val proposal: PlanChangeProposal,
    val decisions: List<PlanChangeDecision>,
    val idempotent: Boolean,
)

/**
 * Business logic of the `/api/factory/plan-change-proposals` surface (Phase 8
 * governed replanning).
 *
 * Orchestrates the pure domain functions — bounded validation
 * ([PlanChangeValidation]), deterministic classification ([PlanChangeClassifier])
 * and governance gating ([GovernanceGateEvaluator]) — with the append-only tenant-
 * scoped store. The service NEVER rewrites active workflow instances: it only
 * records immutable proposals and decision events; Rule-2/3 changes are gated for
 * a human decision or a new definition projection.
 *
 * Idempotency of the submit is keyed by the tuple
 * `(organizationId, workstreamId, workflowId, idempotencyKey)`: a replay with the
 * same canonical payload returns the persisted proposal; a replay with a different
 * payload is a 409 `IDEMPOTENCY_KEY_COLLISION`.
 */
@Service
class PlanChangeProposalService(
    private val repository: Neo4jPlanChangeProposalRepository,
    /**
     * Authoritative workflow instance counter (Lot E): an accepted amendment
     * increments the run's `amendmentSeq` atomically so a result submitted
     * against an obsolete plan revision is rejected by compare-and-set.
     */
    private val workflowRepository: WorkflowRepository,
) {

    private val canonicalMapper = ObjectMapper()

    /**
     * Validate, classify and persist a new plan-change proposal, or replay the
     * already persisted one under the same idempotency tuple.
     *
     * The initial record carries the deterministic [PlanChangeProposal.kind] and
     * [PlanChangeProposal.recommendedVerdict] with the status `PENDING_VALIDATION`;
     * an initial immutable decision event captures the submission so the decision
     * log is complete from the start.
     */
    fun submit(scope: TenantScope, actorId: String, command: PlanChangeSubmitCommand): PlanChangeProposalResult {
        PlanChangeValidation.validateSubmit(command)
        val namespaceId = PlanChangeValidation.requireNamespaceId(command.namespaceId)
        val kind = PlanChangeClassifier.classify(command)
        val recommendedVerdict = GovernanceGateEvaluator.recommendedVerdict(kind)
        val requestHash = canonicalRequestHash(command)

        val existing = repository.findIdempotentNode(scope, command.workflowId, command.idempotencyKey)
        if (existing != null) {
            if (!CanonicalJsonHash.safeEqual(existing.requestHash, requestHash)) {
                planChangeIdempotencyCollision(command.workflowId, command.idempotencyKey)
            }
            val proposal = existing.toDomain()
            return PlanChangeProposalResult(
                proposal = proposal,
                decisions = repository.listDecisions(
                    scope,
                    proposal.namespaceId,
                    proposal.workflowId,
                    proposal.proposalId,
                ),
                idempotent = true,
            )
        }

        val now = Instant.now()
        val proposal = PlanChangeProposal(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = command.workflowId,
            proposalId = "pcp-${UUID.randomUUID()}",
            expectedRevision = command.expectedRevision,
            reasonCode = command.reasonCode,
            summary = command.summary,
            proposalType = command.proposalType,
            affectedStepIds = command.affectedStepIds.distinct(),
            proposedDependencyChanges = command.proposedDependencyChanges,
            proposedScopeChanges = command.proposedScopeChanges,
            evidenceRefs = command.evidenceRefs.distinct(),
            idempotencyKey = command.idempotencyKey,
            kind = kind,
            recommendedVerdict = recommendedVerdict,
            currentStatus = PlanChangeDecisionStatus.PENDING_VALIDATION,
            revision = 1,
            createdAt = now,
            updatedAt = now,
        )
        repository.create(scope, proposal, requestHash)
        val (created, initialDecision) = repository.appendDecision(
            scope = scope,
            namespaceId = namespaceId,
            workflowId = command.workflowId,
            proposalId = proposal.proposalId,
            status = PlanChangeDecisionStatus.PENDING_VALIDATION,
            actorId = actorId,
            reason = "Proposal submitted; deterministic classification '${kind.dbValue}' " +
                "recommends '${recommendedVerdict.dbValue}'",
            idempotencyKey = command.idempotencyKey,
            bumpRevision = false,
        )
        return PlanChangeProposalResult(created, listOf(initialDecision), idempotent = false)
    }

    /** The proposals of [workflowId] in [scope], optionally filtered by their current status. */
    fun list(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        status: PlanChangeDecisionStatus? = null,
    ): List<PlanChangeProposal> = repository.findByWorkflow(scope, namespaceId, workflowId, status)

    /**
     * The proposal of [proposalId] with its full immutable decision log.
     *
     * @throws FactoryHttpException 404 `PLAN_CHANGE_PROPOSAL_NOT_FOUND` when absent in [scope].
     */
    fun get(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
    ): PlanChangeProposalResult {
        val proposal = repository.findById(scope, namespaceId, workflowId, proposalId)
            ?: planChangeProposalNotFound(proposalId)
        return PlanChangeProposalResult(
            proposal = proposal,
            decisions = repository.listDecisions(scope, namespaceId, workflowId, proposalId),
            idempotent = false,
        )
    }

    /**
     * Record a governance decision on a proposal.
     *
     * The [PlanChangeDecideCommand.expectedRevision] fence is enforced against the
     * proposal revision (409 `REVISION_CONFLICT`); the requested outcome is checked
     * against the governance Rules 1–3 by [GovernanceGateEvaluator.assertDecisionAllowed]
     * (409 `PLAN_CHANGE_GATE_REQUIRED`). The decision is appended as an immutable
     * event — the original proposal payload and its history are never rewritten.
     *
     * A replay carrying the same [PlanChangeDecideCommand.idempotencyKey] as the
     * latest decision returns the current state without appending a duplicate.
     */
    fun decide(
        scope: TenantScope,
        actorId: String,
        namespaceId: String,
        workflowId: String,
        proposalId: String,
        command: PlanChangeDecideCommand,
    ): PlanChangeProposalResult {
        PlanChangeValidation.validateDecide(command)
        val proposal = repository.findById(scope, namespaceId, workflowId, proposalId)
            ?: planChangeProposalNotFound(proposalId)
        if (command.expectedRevision != proposal.revision) {
            throw RevisionConflictException(
                "The expected revision is stale.",
                mapOf(
                    "code" to "REVISION_CONFLICT",
                    "expectedRevision" to command.expectedRevision,
                    "revision" to proposal.revision,
                ),
            )
        }
        GovernanceGateEvaluator.assertDecisionAllowed(proposal.kind, command.decision)

        val idempotencyKey = command.idempotencyKey
        if (idempotencyKey != null) {
            val latest = repository.latestDecision(scope, namespaceId, workflowId, proposalId)
            if (latest?.idempotencyKey == idempotencyKey) {
                if (latest.status != command.decision) {
                    planChangeIdempotencyCollision(workflowId, idempotencyKey)
                }
                return PlanChangeProposalResult(
                    proposal = proposal,
                    decisions = repository.listDecisions(scope, namespaceId, workflowId, proposalId),
                    idempotent = true,
                )
            }
        }

        val (updated, _) = repository.appendDecision(
            scope = scope,
            namespaceId = namespaceId,
            workflowId = workflowId,
            proposalId = proposalId,
            status = command.decision,
            actorId = actorId,
            reason = command.reason,
            idempotencyKey = idempotencyKey,
        )
        // An ACCEPTED amendment advances the run's authoritative amendment
        // counter exactly once (the idempotent replay returned above). The
        // counter is distinct from `expectedEnvironmentRevision`; a stale
        // result submission is rejected by compare-and-set against it.
        if (isAcceptedAmendment(command.decision)) {
            workflowRepository.incrementAmendmentSeq(scope, namespaceId, workflowId)
        }
        return PlanChangeProposalResult(
            proposal = updated,
            decisions = repository.listDecisions(scope, namespaceId, workflowId, proposalId),
            idempotent = false,
        )
    }

    /**
     * Whether a decided plan change is an ACCEPTED amendment (applied to the
     * run) and therefore advances the authoritative amendment counter. A
     * `REJECTED` proposal and a `GATE_REQUIRED` proposal (awaiting a human gate)
     * do not change the plan.
     */
    private fun isAcceptedAmendment(status: PlanChangeDecisionStatus): Boolean =
        status == PlanChangeDecisionStatus.AUTO_APPLIED ||
            status == PlanChangeDecisionStatus.REQUIRES_NEW_DEFINITION

    /**
     * Canonical SHA-256 hash of the normalized submit payload (idempotency-key
     * collision detection). Field ordering is normalized by [CanonicalJsonHash].
     */
    private fun canonicalRequestHash(command: PlanChangeSubmitCommand): String {
        val normalized: JsonNode = canonicalMapper.valueToTree(
            linkedMapOf(
                "workflowId" to command.workflowId,
                "namespaceId" to command.namespaceId,
                "expectedRevision" to command.expectedRevision,
                "reasonCode" to command.reasonCode,
                "summary" to command.summary,
                "proposalType" to command.proposalType.wireValue,
                "affectedStepIds" to command.affectedStepIds,
                "proposedDependencyChanges" to command.proposedDependencyChanges.map {
                    linkedMapOf("op" to it.op.wireValue, "fromStepId" to it.fromStepId, "toStepId" to it.toStepId)
                },
                "proposedScopeChanges" to command.proposedScopeChanges?.map {
                    linkedMapOf("op" to it.op.wireValue, "target" to it.target, "detail" to it.detail)
                },
                "evidenceRefs" to command.evidenceRefs,
            ),
        )
        return CanonicalJsonHash.hash(normalized)
    }
}
