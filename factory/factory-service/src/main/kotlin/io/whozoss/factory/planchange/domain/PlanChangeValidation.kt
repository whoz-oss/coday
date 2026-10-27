package io.whozoss.factory.planchange.domain

import io.whozoss.factory.planchange.web.PlanChangeBounds

/**
 * Bounded, deterministic validation of plan-change payloads.
 *
 * Pure validation (no I/O): any missing required field, exceeded bound, invalid
 * identifier or incoherent type/shape combination rejects the payload with a 400
 * `INVALID_PLAN_CHANGE_PROPOSAL`. Strictness mirrors `AgentStepResultValidation`:
 * the declared proposal type must match the payload shape exactly, so a benign
 * declared type can never smuggle a structural change past the governance gates.
 */
object PlanChangeValidation {

    /** Require a non-blank, bounded namespace identifier (400 `INVALID_NAMESPACE_ID`). */
    fun requireNamespaceId(namespaceId: String?): String {
        val value = namespaceId?.trim()?.takeIf { it.isNotEmpty() }
            ?: invalidPlanChangeNamespace()
        if (value.length > PlanChangeBounds.MAX_NAMESPACE_ID) {
            invalidPlanChangeNamespace(
                "namespaceId must be at most ${PlanChangeBounds.MAX_NAMESPACE_ID} characters",
            )
        }
        return value
    }

    /**
     * Validate a submit command, throwing on the first violation.
     *
     * @throws FactoryHttpException 400 `INVALID_PLAN_CHANGE_PROPOSAL` on any violation.
     */
    fun validateSubmit(command: PlanChangeSubmitCommand) {
        requireBoundedId("workflowId", command.workflowId, PlanChangeBounds.MAX_WORKFLOW_ID)
        requireNamespaceId(command.namespaceId)
        if (command.expectedRevision < 1) {
            invalidPlanChangeProposal("expectedRevision must be a positive integer")
        }
        requireBoundedText("reasonCode", command.reasonCode, PlanChangeBounds.MAX_REASON_CODE)
        requireBoundedText("summary", command.summary, PlanChangeBounds.MAX_SUMMARY)
        requireBoundedId("idempotencyKey", command.idempotencyKey, PlanChangeBounds.MAX_IDEMPOTENCY_KEY)

        validateStepIds(command.affectedStepIds)
        validateDependencyChanges(command.proposedDependencyChanges)
        validateScopeChanges(command.proposedScopeChanges)
        validateEvidenceRefs(command.evidenceRefs)
        validateTypeShapeCoherence(command)
    }

    /** Validate a decide command, throwing on the first violation. */
    fun validateDecide(command: PlanChangeDecideCommand) {
        if (command.expectedRevision < 1) {
            invalidPlanChangeDecision("expectedRevision must be a positive integer")
        }
        if (command.decision !in PlanChangeDecisionStatus.DECIDABLE) {
            invalidPlanChangeDecision("decision '${command.decision.dbValue}' is not a recordable outcome")
        }
        command.reason?.let {
            if (it.isBlank() || it.length > PlanChangeBounds.MAX_REASON) {
                invalidPlanChangeDecision(
                    "reason must be non-blank and at most ${PlanChangeBounds.MAX_REASON} characters",
                )
            }
        }
        command.idempotencyKey?.let {
            if (it.isBlank() || it.length > PlanChangeBounds.MAX_IDEMPOTENCY_KEY) {
                invalidPlanChangeDecision(
                    "idempotencyKey must be non-blank and at most ${PlanChangeBounds.MAX_IDEMPOTENCY_KEY} characters",
                )
            }
        }
    }

    private fun validateStepIds(affectedStepIds: List<String>) {
        if (affectedStepIds.size > PlanChangeBounds.MAX_AFFECTED_STEPS) {
            invalidPlanChangeProposal(
                "affectedStepIds cannot exceed ${PlanChangeBounds.MAX_AFFECTED_STEPS} entries",
            )
        }
        affectedStepIds.forEach { requireBoundedId("affectedStepIds[]", it, PlanChangeBounds.MAX_STEP_ID) }
    }

    private fun validateDependencyChanges(changes: List<DependencyChange>) {
        if (changes.size > PlanChangeBounds.MAX_DEPENDENCY_CHANGES) {
            invalidPlanChangeProposal(
                "proposedDependencyChanges cannot exceed ${PlanChangeBounds.MAX_DEPENDENCY_CHANGES} entries",
            )
        }
        changes.forEach { change ->
            requireBoundedId("proposedDependencyChanges[].fromStepId", change.fromStepId, PlanChangeBounds.MAX_STEP_ID)
            requireBoundedId("proposedDependencyChanges[].toStepId", change.toStepId, PlanChangeBounds.MAX_STEP_ID)
            if (change.fromStepId == change.toStepId) {
                invalidPlanChangeProposal("a dependency change cannot reference the same step twice")
            }
        }
    }

    private fun validateScopeChanges(changes: List<ScopeChange>?) {
        changes ?: return
        if (changes.size > PlanChangeBounds.MAX_SCOPE_CHANGES) {
            invalidPlanChangeProposal(
                "proposedScopeChanges cannot exceed ${PlanChangeBounds.MAX_SCOPE_CHANGES} entries",
            )
        }
        changes.forEach { change ->
            requireBoundedText("proposedScopeChanges[].target", change.target, PlanChangeBounds.MAX_SCOPE_TARGET)
            change.detail?.let {
                if (it.isBlank() || it.length > PlanChangeBounds.MAX_SCOPE_DETAIL) {
                    invalidPlanChangeProposal(
                        "proposedScopeChanges[].detail must be non-blank and at most " +
                            "${PlanChangeBounds.MAX_SCOPE_DETAIL} characters",
                    )
                }
            }
        }
    }

    private fun validateEvidenceRefs(evidenceRefs: List<String>) {
        if (evidenceRefs.size > PlanChangeBounds.MAX_EVIDENCE_REFS) {
            invalidPlanChangeProposal(
                "evidenceRefs cannot exceed ${PlanChangeBounds.MAX_EVIDENCE_REFS} entries",
            )
        }
        evidenceRefs.forEach { requireBoundedId("evidenceRefs[]", it, PlanChangeBounds.MAX_EVIDENCE_REF) }
    }

    /**
     * Strict type/shape coherence: the declared type must exactly match the
     * structural payload it carries, mirroring `additionalProperties: false`
     * strictness at the semantic level.
     */
    private fun validateTypeShapeCoherence(command: PlanChangeSubmitCommand) {
        val hasDependencyChanges = command.proposedDependencyChanges.isNotEmpty()
        val hasScopeChanges = !command.proposedScopeChanges.isNullOrEmpty()
        when (command.proposalType) {
            PlanChangeProposalType.DEPENDENCY -> {
                if (!hasDependencyChanges) {
                    invalidPlanChangeProposal("a DEPENDENCY proposal requires at least one proposedDependencyChange")
                }
                if (hasScopeChanges) {
                    invalidPlanChangeProposal("a DEPENDENCY proposal cannot carry proposedScopeChanges")
                }
            }

            PlanChangeProposalType.SCOPE -> {
                if (!hasScopeChanges) {
                    invalidPlanChangeProposal("a SCOPE proposal requires at least one proposedScopeChange")
                }
                if (hasDependencyChanges) {
                    invalidPlanChangeProposal("a SCOPE proposal cannot carry proposedDependencyChanges")
                }
            }

            PlanChangeProposalType.RETRY,
            PlanChangeProposalType.PATH_SELECTION,
            PlanChangeProposalType.OPTIONAL_STEP,
            PlanChangeProposalType.NEW_STEP,
            PlanChangeProposalType.CONTRACT_OR_ORACLE,
            -> {
                if (hasDependencyChanges || hasScopeChanges) {
                    invalidPlanChangeProposal(
                        "a ${command.proposalType.wireValue} proposal cannot carry dependency or scope changes; " +
                            "declare the matching proposal type instead",
                    )
                }
            }
        }
        if (command.proposalType == PlanChangeProposalType.RETRY && command.affectedStepIds.isEmpty()) {
            invalidPlanChangeProposal("a RETRY proposal must reference at least one affected step")
        }
        if (command.proposalType == PlanChangeProposalType.OPTIONAL_STEP && command.affectedStepIds.isEmpty()) {
            invalidPlanChangeProposal("an OPTIONAL_STEP proposal must reference the optional steps to activate")
        }
    }

    private fun requireBoundedId(field: String, value: String, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) {
            invalidPlanChangeProposal("$field must be non-blank and at most $maxLength characters")
        }
    }

    private fun requireBoundedText(field: String, value: String, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) {
            invalidPlanChangeProposal("$field must be non-blank and at most $maxLength characters")
        }
    }
}
