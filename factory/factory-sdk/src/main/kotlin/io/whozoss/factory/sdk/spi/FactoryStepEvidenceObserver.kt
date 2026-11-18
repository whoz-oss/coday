package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/** Read-only Git objects from the Run-bound Case workspace through a trusted host transport. */
interface TrustedGitObjectReader {
    fun sourceRepository(): String?
    fun commitExists(commit: String): Boolean
    fun readBlob(commit: String, path: String, maxBytes: Int): ByteArray?
}

/**
 * External observation of an agent result. Factory calls this outside its
 * finalization transaction, with a Case worktree resolved from persisted workflow
 * state. A remote Git reader is supplied when Factory cannot share its disk.
 * The returned facts are bounded metadata, never a document/blob transport.
 */
data class StepEvidenceObservationContext(
    val run: RunView,
    val flowType: String,
    val stepId: String,
    val agentName: String,
    val attemptId: String,
    val worktreePath: String,
    val report: Map<String, Any?>,
    val gitObjects: TrustedGitObjectReader? = null,
)

sealed interface StepEvidenceObservation {
    data class Observed(val facts: Map<String, Any?>) : StepEvidenceObservation
    /** Retryable external failure; the step must not advance. */
    data class Unavailable(val code: String, val message: String) : StepEvidenceObservation
    /** Invalid agent-supplied content; the ordinary validator may fail the step. */
    data class Invalid(val code: String, val message: String) : StepEvidenceObservation
}

interface FactoryStepEvidenceObserver : ExtensionPoint {
    fun supports(flowType: String, stepId: String, agentName: String): Boolean = false
    fun observe(context: StepEvidenceObservationContext): StepEvidenceObservation
}
