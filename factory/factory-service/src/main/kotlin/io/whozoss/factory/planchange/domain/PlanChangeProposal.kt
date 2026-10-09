package io.whozoss.factory.planchange.domain

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.planchange.persistence.PlanChangeProposalNode
import java.time.Instant

/**
 * Immutable domain model of a governed plan-change proposal (Phase 8).
 *
 * A proposal is **append-only**: the submitted payload (reason, affected steps,
 * proposed changes, evidence references) and the deterministic classification
 * ([kind], [recommendedVerdict]) never change after creation. The only mutable
 * fields are the derived cache [currentStatus] / [revision] / [updatedAt], whose
 * source of truth is the append-only `PlanChangeDecision` log — so the plan
 * history, causality and evidence are preserved by construction.
 *
 * The tenant scope is the composite `(organizationId, workstreamId, namespaceId)`;
 * `organizationId` and `workstreamId` always come from the trusted caller scope,
 * never from client input.
 */
data class PlanChangeProposal(
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val proposalId: String,
    /** Workflow definition revision the proposer based its proposal on (fence). */
    val expectedRevision: Int,
    val reasonCode: String,
    val summary: String,
    val proposalType: PlanChangeProposalType,
    val affectedStepIds: List<String> = emptyList(),
    val proposedDependencyChanges: List<DependencyChange> = emptyList(),
    val proposedScopeChanges: List<ScopeChange>? = null,
    val evidenceRefs: List<String> = emptyList(),
    val idempotencyKey: String,
    /** Deterministic classification computed by [PlanChangeClassifier]. */
    val kind: PlanChangeKind,
    /** Deterministic governance verdict computed by [GovernanceGateEvaluator]. */
    val recommendedVerdict: PlanChangeDecisionStatus,
    /** Derived cache of the latest decision event; the decision log is the source of truth. */
    val currentStatus: PlanChangeDecisionStatus,
    val revision: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

private val planChangeJson = ObjectMapper()

/** Map a domain [PlanChangeProposal] to its persistable [PlanChangeProposalNode]. */
fun PlanChangeProposal.toNode(requestHash: String): PlanChangeProposalNode =
    PlanChangeProposalNode(
        id = PlanChangeProposalNode.compositeId(organizationId, workstreamId, namespaceId, workflowId, proposalId),
        organizationId = organizationId,
        workstreamId = workstreamId,
        namespaceId = namespaceId,
        workflowId = workflowId,
        proposalId = proposalId,
        expectedRevision = expectedRevision,
        reasonCode = reasonCode,
        summary = summary,
        proposalType = proposalType.wireValue,
        affectedStepIds = affectedStepIds,
        dependencyChangesJson = planChangeJson.writeValueAsString(
            proposedDependencyChanges.map {
                linkedMapOf("op" to it.op.wireValue, "fromStepId" to it.fromStepId, "toStepId" to it.toStepId)
            },
        ),
        scopeChangesJson = proposedScopeChanges?.let { changes ->
            planChangeJson.writeValueAsString(
                changes.map {
                    linkedMapOf("op" to it.op.wireValue, "target" to it.target, "detail" to it.detail)
                },
            )
        },
        evidenceRefs = evidenceRefs,
        idempotencyKey = idempotencyKey,
        requestHash = requestHash,
        kind = kind.dbValue,
        recommendedVerdict = recommendedVerdict.dbValue,
        currentStatus = currentStatus.dbValue,
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** Map a persisted [PlanChangeProposalNode] to its domain [PlanChangeProposal]. */
fun PlanChangeProposalNode.toDomain(): PlanChangeProposal =
    PlanChangeProposal(
        organizationId = organizationId,
        workstreamId = workstreamId,
        namespaceId = namespaceId,
        workflowId = workflowId,
        proposalId = proposalId,
        expectedRevision = expectedRevision,
        reasonCode = reasonCode,
        summary = summary,
        proposalType = PlanChangeProposalType.parse(proposalType)
            ?: throw IllegalStateException("Unknown plan change proposal type: $proposalType"),
        affectedStepIds = affectedStepIds,
        proposedDependencyChanges = parseDependencyChanges(dependencyChangesJson),
        proposedScopeChanges = scopeChangesJson?.let { parseScopeChanges(it) },
        evidenceRefs = evidenceRefs,
        idempotencyKey = idempotencyKey,
        kind = PlanChangeKind.fromDbValue(kind),
        recommendedVerdict = PlanChangeDecisionStatus.fromDbValue(recommendedVerdict),
        currentStatus = PlanChangeDecisionStatus.fromDbValue(currentStatus),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun parseDependencyChanges(json: String): List<DependencyChange> =
    planChangeJson.readTree(json).takeIf { it.isArray }?.map { entry ->
        DependencyChange(
            op = DependencyOp.parse(entry.get("op")?.asText())
                ?: throw IllegalStateException("Unknown dependency op in $json"),
            fromStepId = entry.get("fromStepId")?.asText()
                ?: throw IllegalStateException("Missing fromStepId in $json"),
            toStepId = entry.get("toStepId")?.asText()
                ?: throw IllegalStateException("Missing toStepId in $json"),
        )
    } ?: throw IllegalStateException("Corrupt dependency changes payload: $json")

private fun parseScopeChanges(json: String): List<ScopeChange> =
    planChangeJson.readTree(json).takeIf { it.isArray }?.map { entry ->
        ScopeChange(
            op = ScopeChangeOp.parse(entry.get("op")?.asText())
                ?: throw IllegalStateException("Unknown scope change op in $json"),
            target = entry.get("target")?.asText()
                ?: throw IllegalStateException("Missing target in $json"),
            detail = entry.get("detail")?.takeIf { it.isTextual }?.asText(),
        )
    } ?: throw IllegalStateException("Corrupt scope changes payload: $json")
