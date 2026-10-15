package io.whozoss.factory.planchange.domain

/**
 * Pure governance-gate evaluation of a classified plan-change proposal (Phase 8
 * governed replanning).
 *
 * Like [PlanChangeClassifier], every function here is deterministic and I/O-free:
 * the same kind always yields the same recommended verdict, and the same
 * `(kind, requestedDecision)` pair is always accepted or always rejected.
 *
 * Governance rules encoded:
 *  - **Rule 1 — self-application** is strictly limited to explicitly pre-declared
 *    variations of the current definition ([PlanChangeKind.RETRY_NO_PLAN_CHANGE],
 *    [PlanChangeKind.PATH_SELECTION], [PlanChangeKind.OPTIONAL_STEP_ACTIVATION]):
 *    only those kinds may ever be recorded `AUTO_APPLIED`.
 *  - **Rule 2 — structural / contract / oracle / scope / DAG changes**
 *    ([PlanChangeKind.NEW_STEP_PROPOSAL], [PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL],
 *    [PlanChangeKind.SCOPE_CHANGE_PROPOSAL], [PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL])
 *    never auto-apply: they require a human governance gate (`GATE_REQUIRED`) or a
 *    new definition / successor workflow projection (`REQUIRES_NEW_DEFINITION`).
 *    Active workflow instances are never rewritten silently.
 *  - **Rule 3 — human gate** is mandatory for scope expansion, deny weakening or
 *    governance policy changes: such proposals can never be `AUTO_APPLIED`.
 */
object GovernanceGateEvaluator {

    /** Kinds a workflow instance may self-apply (Rule 1 pre-declared variations). */
    val SELF_APPLICABLE_KINDS: Set<PlanChangeKind> = setOf(
        PlanChangeKind.RETRY_NO_PLAN_CHANGE,
        PlanChangeKind.PATH_SELECTION,
        PlanChangeKind.OPTIONAL_STEP_ACTIVATION,
    )

    /**
     * The deterministic recommended verdict of a classified proposal:
     *  - Rule-1 kinds → `AUTO_APPLIED` (within the current definition limits);
     *  - dependency / scope changes → `GATE_REQUIRED` (Rules 2–3, human gate);
     *  - new-step / contract-or-oracle changes → `REQUIRES_NEW_DEFINITION` (Rule 2,
     *    successor definition projection).
     */
    fun recommendedVerdict(kind: PlanChangeKind): PlanChangeDecisionStatus =
        when (kind) {
            PlanChangeKind.RETRY_NO_PLAN_CHANGE,
            PlanChangeKind.PATH_SELECTION,
            PlanChangeKind.OPTIONAL_STEP_ACTIVATION,
            -> PlanChangeDecisionStatus.AUTO_APPLIED

            PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL,
            PlanChangeKind.SCOPE_CHANGE_PROPOSAL,
            -> PlanChangeDecisionStatus.GATE_REQUIRED

            PlanChangeKind.NEW_STEP_PROPOSAL,
            PlanChangeKind.CONTRACT_OR_ORACLE_CHANGE_PROPOSAL,
            -> PlanChangeDecisionStatus.REQUIRES_NEW_DEFINITION
        }

    /**
     * Enforce Rules 1–3 on a decision about to be recorded.
     *
     * `AUTO_APPLIED` is only ever allowed for a Rule-1 self-applicable kind;
     * `REJECTED`, `GATE_REQUIRED` and `REQUIRES_NEW_DEFINITION` are allowed for any
     * kind (they are always at least as strict as the recommended verdict).
     *
     * @throws FactoryHttpException 409 `PLAN_CHANGE_GATE_REQUIRED` when the requested
     * decision would violate the governance rules.
     */
    fun assertDecisionAllowed(kind: PlanChangeKind, requested: PlanChangeDecisionStatus) {
        require(requested in PlanChangeDecisionStatus.DECIDABLE) {
            "PENDING_VALIDATION is not a recordable decision"
        }
        if (requested == PlanChangeDecisionStatus.AUTO_APPLIED && kind !in SELF_APPLICABLE_KINDS) {
            planChangeGateRequired(kind, requested)
        }
    }
}
