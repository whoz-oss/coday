package io.whozoss.factory.sdk.spi

/**
 * Severity of a domain or verification finding tracked at the parent Run level.
 */
enum class FindingSeverity {
    CRITICAL,
    MAJOR,
    MINOR,
    INFO,
}

/**
 * Lifecycle status of a finding tracked on a parent Run (D10: findings are
 * persistent across revisions until explicitly acknowledged).
 */
enum class FindingStatus {
    OPEN,
    RESOLVED,
    SUPERSEDED,
    WONT_FIX,
}

/**
 * New finding emitted by a step validator or by a selector's `reduce` phase.
 */
data class NewFinding(
    val id: String? = null,
    val code: String,
    val severity: FindingSeverity,
    val message: String,
    val stepId: String? = null,
    val filePath: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * Read-only view of a finding persisted on a parent Run.
 */
data class FindingView(
    val id: String,
    val code: String,
    val severity: FindingSeverity,
    val status: FindingStatus,
    val message: String,
    val sourceFragmentId: String? = null,
    val stepId: String? = null,
    val filePath: String? = null,
    val openedAtRevision: Long = 0L,
    val resolutionReason: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * Explicit status transition for an existing finding.
 */
data class FindingResolution(
    val status: FindingStatus,
    val reason: String,
)

/**
 * Read-only summary of a single step execution inside a completed fragment.
 */
data class StepOutcomeView(
    val stepId: String,
    val kind: String,
    val status: String,
    val report: Map<String, Any?> = emptyMap(),
    val oracleResults: List<Map<String, Any?>> = emptyList(),
    val humanDecision: Map<String, Any?>? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/**
 * Read-only view of a fragment (short workflow DAG) in a parent Run's history.
 */
data class FragmentView(
    val fragmentId: String,
    val ordinal: Int,
    val flowType: String,
    val status: String,
    val inputs: Map<String, Any?> = emptyMap(),
    val decisionReason: String? = null,
)

/**
 * Read-only snapshot of a parent Run handed to plugin SPIs.
 */
data class RunView(
    val runId: String,
    val runType: String,
    val selectorVersion: String = "1.0.0",
    val ticket: String? = null,
    val domainState: Map<String, Any?> = emptyMap(),
    val domainRevision: Long = 0L,
    val findings: List<FindingView> = emptyList(),
    val fragments: List<FragmentView> = emptyList(),
    val loopCounts: Map<String, Int> = emptyMap(),
)

/**
 * Outcome of a completed fragment handed to [FactoryFlowSelector.reduce].
 */
data class FragmentOutcome(
    val fragmentId: String,
    val flowType: String,
    val status: String,
    val stepOutcomes: List<StepOutcomeView> = emptyList(),
)

/**
 * State update returned by [FactoryFlowSelector.start] and [FactoryFlowSelector.reduce].
 *
 * When [newDomainState] is non-null, the host atomically replaces `domainState`
 * and increments `domainRevision` under optimistic concurrency control.
 */
data class RunUpdate(
    val newDomainState: Map<String, Any?>? = null,
    val expectedDomainRevision: Long? = null,
    val addedFindings: List<NewFinding> = emptyList(),
    val findingResolutions: Map<String, FindingResolution> = emptyMap(),
)

/**
 * Deterministic routing decision returned by [FactoryFlowSelector].
 */
sealed interface FlowDecision {
    /**
     * Instantiate and run a new fragment from the catalog.
     */
    data class Apply(
        val flowType: String,
        val inputs: Map<String, Any?> = emptyMap(),
        val reason: String,
        val loopKey: String? = null,
        val maxIterations: Int? = null,
    ) : FlowDecision

    /**
     * Mark the parent Run as completed.
     */
    data class Complete(
        val reason: String,
    ) : FlowDecision

    /**
     * Suspend the parent Run and escalate to a human authority.
     */
    data class EscalateHuman(
        val question: String,
        val options: List<String> = emptyList(),
        val reason: String,
    ) : FlowDecision

    /**
     * Abort the parent Run with an unrecoverable failure.
     */
    data class Fail(
        val code: String,
        val reason: String,
    ) : FlowDecision
}
