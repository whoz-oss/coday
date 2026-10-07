package io.whozoss.factory.planchange.domain

/**
 * Deterministic classification of a plan-change proposal into the [PlanChangeKind]
 * taxonomy.
 *
 * This is a **pure function**: no I/O, no clock, no randomness. The same payload
 * always yields the same kind, which makes the classification unit-provable and the
 * governance decision reproducible.
 *
 * The classification combines the submitter-declared [PlanChangeProposalType] with
 * the payload shape, evaluating the **most structural** signal first: a structural
 * payload (dependency edges, scope edits) can never be downgraded to a benign
 * self-applicable kind by declaring a benign type. Validation of the type/shape
 * coherence is [PlanChangeValidation]'s job; the classifier stays total and
 * deterministic even on incoherent shapes.
 */
object PlanChangeClassifier {

    /** Classify [command] into its deterministic taxonomy kind. */
    fun classify(command: PlanChangeSubmitCommand): PlanChangeKind =
        classify(
            proposalType = command.proposalType,
            proposedDependencyChanges = command.proposedDependencyChanges,
            proposedScopeChanges = command.proposedScopeChanges,
        )

    /**
     * Classify by declared type + payload shape, most structural first:
     *   1. a declared new step is always a [PlanChangeKind.NEW_STEP_PROPOSAL];
     *   2. a declared contract/oracle change is always a
     *      [PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL];
     *   3. any proposed dependency edge (or a declared dependency change) is a
     *      [PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL];
     *   4. any proposed scope change (or a declared scope change) is a
     *      [PlanChangeKind.SCOPE_CHANGE_PROPOSAL];
     *   5. a declared pathway selection is a [PlanChangeKind.PATH_SELECTION];
     *   6. a declared optional-step activation is an
     *      [PlanChangeKind.OPTIONAL_STEP_ACTIVATION];
     *   7. anything else is a plain [PlanChangeKind.RETRY_NO_PLAN_CHANGE].
     */
    fun classify(
        proposalType: PlanChangeProposalType,
        proposedDependencyChanges: List<DependencyChange>,
        proposedScopeChanges: List<ScopeChange>?,
    ): PlanChangeKind {
        if (proposalType == PlanChangeProposalType.NEW_STEP) return PlanChangeKind.NEW_STEP_PROPOSAL
        if (proposalType == PlanChangeProposalType.CONTRACT_OR_ORACLE) {
            return PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL
        }
        if (proposalType == PlanChangeProposalType.DEPENDENCY || proposedDependencyChanges.isNotEmpty()) {
            return PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL
        }
        if (proposalType == PlanChangeProposalType.SCOPE || !proposedScopeChanges.isNullOrEmpty()) {
            return PlanChangeKind.SCOPE_CHANGE_PROPOSAL
        }
        return when (proposalType) {
            PlanChangeProposalType.PATH_SELECTION -> PlanChangeKind.PATH_SELECTION
            PlanChangeProposalType.OPTIONAL_STEP -> PlanChangeKind.OPTIONAL_STEP_ACTIVATION
            else -> PlanChangeKind.RETRY_NO_PLAN_CHANGE
        }
    }
}
