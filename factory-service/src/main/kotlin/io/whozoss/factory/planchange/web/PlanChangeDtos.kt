package io.whozoss.factory.planchange.web

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.whozoss.factory.planchange.domain.DependencyChange
import io.whozoss.factory.planchange.domain.DependencyOp
import io.whozoss.factory.planchange.domain.PlanChangeDecideCommand
import io.whozoss.factory.planchange.domain.PlanChangeDecision
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeProposal
import io.whozoss.factory.planchange.domain.PlanChangeProposalType
import io.whozoss.factory.planchange.domain.PlanChangeSubmitCommand
import io.whozoss.factory.planchange.domain.ScopeChange
import io.whozoss.factory.planchange.domain.ScopeChangeOp
import io.whozoss.factory.planchange.domain.invalidPlanChangeDecision
import io.whozoss.factory.planchange.domain.invalidPlanChangeProposal
import io.whozoss.factory.planchange.service.PlanChangeProposalResult
import java.time.Instant

/**
 * Strict bounds applied to every plan-change payload the surface accepts,
 * aligned with the bounded schemas of
 * `app_docs/workstream_agent_cartography_and_contracts.md`.
 */
object PlanChangeBounds {
    const val MAX_WORKFLOW_ID = 128
    const val MAX_NAMESPACE_ID = 128
    const val MAX_SUMMARY = 2000
    const val MAX_AFFECTED_STEPS = 100
    const val MAX_DEPENDENCY_CHANGES = 50
    const val MAX_SCOPE_CHANGES = 50
    const val MAX_EVIDENCE_REFS = 100
    const val MAX_EVIDENCE_REF = 512
    const val MAX_REASON_CODE = 64
    const val MAX_IDEMPOTENCY_KEY = 128
    const val MAX_STEP_ID = 128
    const val MAX_REASON = 500
    const val MAX_SCOPE_TARGET = 128
    const val MAX_SCOPE_DETAIL = 2000
}

/**
 * Base of the strictly-shaped request DTOs: any JSON property not mapped to a
 * declared field is captured (`additionalProperties: false` equivalent) and
 * rejected by the `toCommand()` conversion with a 400
 * `INVALID_PLAN_CHANGE_PROPOSAL` / `INVALID_PLAN_CHANGE_DECISION`.
 */
abstract class StrictRequest {
    @get:JsonIgnore
    val unknownFields: MutableMap<String, Any?> = mutableMapOf()

    @JsonAnySetter
    fun captureUnknownField(name: String, value: Any?) {
        unknownFields[name] = value
    }

    protected fun rejectUnknownFields(error: (String) -> Nothing) {
        if (unknownFields.isNotEmpty()) {
            error("Unknown fields: ${unknownFields.keys.sorted().joinToString(", ")}")
        }
    }
}

/** One proposed dependency edge change (op + the two existing step ids). */
data class DependencyChangeDto(
    val op: String? = null,
    val fromStepId: String? = null,
    val toStepId: String? = null,
) {
    fun toDomain(): DependencyChange =
        DependencyChange(
            op = DependencyOp.parse(op)
                ?: invalidPlanChangeProposal("proposedDependencyChanges[].op must be ADD or REMOVE"),
            fromStepId = fromStepId
                ?: invalidPlanChangeProposal("proposedDependencyChanges[].fromStepId is required"),
            toStepId = toStepId
                ?: invalidPlanChangeProposal("proposedDependencyChanges[].toStepId is required"),
        )

    companion object {
        fun of(change: DependencyChange): DependencyChangeDto =
            DependencyChangeDto(
                op = change.op.wireValue,
                fromStepId = change.fromStepId,
                toStepId = change.toStepId,
            )
    }
}

/** One proposed scope change (op + target + optional detail). */
data class ScopeChangeDto(
    val op: String? = null,
    val target: String? = null,
    val detail: String? = null,
) {
    fun toDomain(): ScopeChange =
        ScopeChange(
            op = ScopeChangeOp.parse(op)
                ?: invalidPlanChangeProposal("proposedScopeChanges[].op must be EXPAND, REDUCE or MODIFY"),
            target = target
                ?: invalidPlanChangeProposal("proposedScopeChanges[].target is required"),
            detail = detail,
        )

    companion object {
        fun of(change: ScopeChange): ScopeChangeDto =
            ScopeChangeDto(
                op = change.op.wireValue,
                target = change.target,
                detail = change.detail,
            )
    }
}

/**
 * Plan-change proposal submission body. Nullable fields so a malformed body is a
 * clean 400 `INVALID_PLAN_CHANGE_PROPOSAL`; [toCommand] parses the discriminators
 * and materializes the validated command shape.
 */
data class SubmitPlanChangeRequest(
    val workflowId: String? = null,
    val namespaceId: String? = null,
    val expectedRevision: Int? = null,
    val reasonCode: String? = null,
    val summary: String? = null,
    val proposalType: String? = null,
    val affectedStepIds: List<String>? = null,
    val proposedDependencyChanges: List<DependencyChangeDto>? = null,
    val proposedScopeChanges: List<ScopeChangeDto>? = null,
    val evidenceRefs: List<String>? = null,
    val idempotencyKey: String? = null,
) : StrictRequest() {

    fun toCommand(): PlanChangeSubmitCommand {
        rejectUnknownFields { invalidPlanChangeProposal(it) }
        return PlanChangeSubmitCommand(
            workflowId = workflowId ?: invalidPlanChangeProposal("workflowId is required"),
            namespaceId = namespaceId ?: "",
            expectedRevision = expectedRevision
                ?: invalidPlanChangeProposal("expectedRevision is required"),
            reasonCode = reasonCode ?: invalidPlanChangeProposal("reasonCode is required"),
            summary = summary ?: invalidPlanChangeProposal("summary is required"),
            proposalType = PlanChangeProposalType.parse(proposalType)
                ?: invalidPlanChangeProposal(
                    "proposalType must be one of ${PlanChangeProposalType.entries.joinToString(", ") { it.wireValue }}",
                ),
            affectedStepIds = affectedStepIds.orEmpty(),
            proposedDependencyChanges = proposedDependencyChanges.orEmpty().map { it.toDomain() },
            proposedScopeChanges = proposedScopeChanges?.map { it.toDomain() },
            evidenceRefs = evidenceRefs.orEmpty(),
            idempotencyKey = idempotencyKey ?: invalidPlanChangeProposal("idempotencyKey is required"),
        )
    }
}

/**
 * Governance decision body of `POST /{proposalId}/decide`. [decision] must be one
 * of `AUTO_APPLIED`, `GATE_REQUIRED`, `REQUIRES_NEW_DEFINITION`, `REJECTED`;
 * [expectedRevision] is the mandatory optimistic-locking fence.
 */
data class DecidePlanChangeRequest(
    val expectedRevision: Int? = null,
    val decision: String? = null,
    val reason: String? = null,
    val idempotencyKey: String? = null,
) : StrictRequest() {

    fun toCommand(): PlanChangeDecideCommand {
        rejectUnknownFields { invalidPlanChangeDecision(it) }
        return PlanChangeDecideCommand(
            expectedRevision = expectedRevision
                ?: invalidPlanChangeDecision("expectedRevision is required"),
            decision = PlanChangeDecisionStatus.parseDecision(decision)
                ?: invalidPlanChangeDecision(
                    "decision must be one of " +
                        PlanChangeDecisionStatus.DECIDABLE.joinToString(", ") { it.dbValue },
                ),
            reason = reason,
            idempotencyKey = idempotencyKey,
        )
    }
}

/** One immutable decision event of the proposal's append-only log. */
data class PlanChangeDecisionResponse(
    val sequence: Long,
    val status: String,
    val actorId: String,
    val reason: String?,
    val recordedAt: Instant,
)

/**
 * Secret-free proposal view: the immutable payload, the deterministic
 * classification and recommended verdict, the derived current status and the full
 * decision timeline. The canonical request hash is never exposed.
 */
data class PlanChangeProposalResponse(
    val proposalId: String,
    val workflowId: String,
    val namespaceId: String,
    val workstreamId: String,
    val reasonCode: String,
    val summary: String,
    val proposalType: String,
    val kind: String,
    val recommendedVerdict: String,
    val status: String,
    val expectedRevision: Int,
    val affectedStepIds: List<String>,
    val proposedDependencyChanges: List<DependencyChangeDto>,
    val proposedScopeChanges: List<ScopeChangeDto>?,
    val evidenceRefs: List<String>,
    val revision: Int,
    val idempotent: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val decisions: List<PlanChangeDecisionResponse>,
)

/** Map a domain decision event to its wire response. */
fun PlanChangeDecision.toResponse(): PlanChangeDecisionResponse =
    PlanChangeDecisionResponse(
        sequence = sequence,
        status = status.dbValue,
        actorId = actorId,
        reason = reason,
        recordedAt = recordedAt,
    )

/** Map a domain proposal plus its decision log to its wire response. */
fun PlanChangeProposal.toResponse(
    decisions: List<PlanChangeDecision>,
    idempotent: Boolean,
): PlanChangeProposalResponse =
    PlanChangeProposalResponse(
        proposalId = proposalId,
        workflowId = workflowId,
        namespaceId = namespaceId,
        workstreamId = workstreamId,
        reasonCode = reasonCode,
        summary = summary,
        proposalType = proposalType.wireValue,
        kind = kind.dbValue,
        recommendedVerdict = recommendedVerdict.dbValue,
        status = currentStatus.dbValue,
        expectedRevision = expectedRevision,
        affectedStepIds = affectedStepIds,
        proposedDependencyChanges = proposedDependencyChanges.map { DependencyChangeDto.of(it) },
        proposedScopeChanges = proposedScopeChanges?.map { ScopeChangeDto.of(it) },
        evidenceRefs = evidenceRefs,
        revision = revision,
        idempotent = idempotent,
        createdAt = createdAt,
        updatedAt = updatedAt,
        decisions = decisions.map { it.toResponse() },
    )

/** Map a service result to its wire response. */
fun PlanChangeProposalResult.toResponse(): PlanChangeProposalResponse =
    proposal.toResponse(decisions, idempotent)
