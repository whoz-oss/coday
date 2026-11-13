package io.whozoss.factory.workflow.domain

/** Named legacy obligations shared by byte-compatible dispatch and deliberate adoption. */
data class LegacyAgentContract(
    val framingObligations: List<String>,
    val runBriefObligation: String,
    val guardrailObligations: List<String>,
    val mission: String,
    val deliverablesHeader: String,
    val deliverableOptions: List<String>,
    val clarificationObligation: String,
) {
    fun adoption(): WorkflowStepContract = WorkflowStepContract(
        entry = framingObligations + guardrailObligations,
        mission = mission,
        exit = listOf(runBriefObligation, deliverablesHeader) + deliverableOptions,
    )

    fun renderHandoff(): String = buildString {
        framingObligations.forEach { appendLine("- $it") }
        appendLine("- $runBriefObligation")
        guardrailObligations.forEach { appendLine("- $it") }
    }

    fun renderDeliverables(): String = buildString {
        appendLine("- $deliverablesHeader")
        deliverableOptions.forEach { appendLine("  - $it") }
        appendLine("- $clarificationObligation")
    }
}

/** Single source of legacy agent obligations for dispatch and deliberate adoption. */
object LegacyStepContract {
    fun obligations(step: WorkflowStepDefinition): LegacyAgentContract {
        val root = step.dependsOn.isEmpty()
        return LegacyAgentContract(
            framingObligations = if (root) listOf(
                "This is an entry step. Frame the handoff before completing it: objective, scope and exclusions, criteria explicitly supplied by the user or workflow, constraints, and open questions.",
                "If the initial intention is absent or ambiguous, call queryUser and wait for the answer. Do not submit FAIL merely because clarification is needed.",
                "If queryUser is unavailable, do not bypass clarification by guessing or silently widening scope; report the clarification blocker only as a justified terminal failure when no supported continuation exists.",
            ) else listOf(
                "Consume the structured dependency inputs and the run-brief handoff above while following this step's own instructions; the original user request remains the governing intention.",
            ),
            runBriefObligation = if (root)
                "Include one durable Markdown artifact in the normal artifacts list with kind 'run-brief', encoding 'markdown', and content sections: Objectif, Périmètre et exclusions, Critères, Contraintes, Questions ouvertes."
            else
                "Preserve every run-brief artifact received from direct dependencies unchanged in this step's artifacts so later steps can relay the correct branch framing. Add separate artifacts for this step's own work; never rewrite or merge independent framings.",
            guardrailObligations = if (root) listOf("Do not invent criteria or turn a request for analysis into an implementation task.") else emptyList(),
            mission = step.name,
            deliverablesHeader = "Choose deliverables from the work actually requested when the definition has no explicit deliverable metadata:",
            deliverableOptions = listOf(
                "Analysis or design: provide reasoned findings, decisions/options, constraints, open questions, and a durable Markdown artifact; do not implement unless requested.",
                "Implementation: provide the scoped changes and concrete verification evidence available under the run policy; do not claim checks that were not run.",
                "Review: provide prioritized findings with locations/evidence and residual risks; do not modify code unless requested.",
            ),
            clarificationObligation = "If clarification is required, call queryUser, wait for the answer, then continue the same step. If that tool is unavailable, do not guess or bypass the clarification requirement.",
        )
    }

    fun generic(step: WorkflowStepDefinition): WorkflowStepContract = obligations(step).adoption()

    /** Metadata lives beside the canonical definition; never participates in its hash. */
    fun metadata(step: WorkflowStepDefinition): Map<String, Any?> {
        val summary = when {
            step.responsibility.kind != ResponsibilityKind.AGENT ->
                "This step uses the existing approval or verification implementation, not an agent prompt."
            step.responsibility.name == "Gitay" ->
                "Factory selects the authoritative branch, restricts Git tools, requires unchanged HEAD and an attached expected branch, and forbids edits, commit, push, fetch, stash, reset and merge."
            step.responsibility.name == "A11yReviewer" ->
                "Factory computes authoritative PR files and changed ranges, limits the accessibility audit to that scope, and fails closed when scope is unavailable."
            else -> "Factory frames entry requests and preserves dependency run-brief handoffs, with deliverables appropriate to analysis, implementation or review."
        }
        val adoptable = step.responsibility.kind == ResponsibilityKind.AGENT && step.responsibility.name !in setOf("Gitay", "A11yReviewer")
        return buildMap {
            put("adoptable", adoptable)
            put("summary", summary)
            if (adoptable) putAll(generic(step).toJson())
        }
    }
}
