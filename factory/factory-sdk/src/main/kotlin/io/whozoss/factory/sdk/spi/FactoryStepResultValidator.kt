package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Availability status of the workspace delta captured around an agent turn.
 */
enum class WorkspaceCaptureStatus {
    COMPLETE,
    DEGRADED,
    UNAVAILABLE,
}

/**
 * Before/after snapshot of a watched file provided by AgentOS.
 */
data class WatchedFileSnapshot(
    val path: String,
    val existsBefore: Boolean,
    val sha256Before: String? = null,
    val existsAfter: Boolean,
    val sha256After: String? = null,
    val patchAfter: String? = null,
)

/**
 * Workspace delta captured by the Factory from AgentOS before and after an
 * agent turn.
 */
data class WorkspaceDelta(
    val captureStatus: WorkspaceCaptureStatus = WorkspaceCaptureStatus.COMPLETE,
    val headShaBefore: String? = null,
    val headShaAfter: String? = null,
    val changedFilesDuringTurn: Map<String, String> = emptyMap(),
    val watchedFiles: Map<String, WatchedFileSnapshot> = emptyMap(),
    val degradedReason: String? = null,
)

/**
 * Summary of a tool invocation observed during an agent turn.
 */
data class ToolEventView(
    val toolName: String,
    val eventType: String,
    val summary: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

/**
 * Context passed to [FactoryStepResultValidator.validate] when an agent step
 * submits its structured report.
 */
data class StepValidationContext(
    val run: RunView,
    val flowType: String,
    val stepId: String,
    val agentName: String,
    val fragmentInputs: Map<String, Any?> = emptyMap(),
    val report: Map<String, Any?> = emptyMap(),
    val workspaceDelta: WorkspaceDelta = WorkspaceDelta(),
    val toolEvents: List<ToolEventView> = emptyList(),
)

/**
 * Verdict returned by [FactoryStepResultValidator.validate] before the host
 * seals the step status.
 */
sealed interface StepValidationVerdict {
    data class Accepted(
        val newDomainState: Map<String, Any?>? = null,
        val expectedDomainRevision: Long? = null,
        val addedFindings: List<NewFinding> = emptyList(),
        val findingResolutions: Map<String, FindingResolution> = emptyMap(),
    ) : StepValidationVerdict

    /**
     * Marks the step as `FAILED` and records [findings] as evidence.
     */
    data class Rejected(
        val reasonCode: String,
        val findings: List<NewFinding> = emptyList(),
    ) : StepValidationVerdict
}

/**
 * PF4J extension point synchronously validating agent step results and
 * workspace deltas before the step transition is sealed.
 */
interface FactoryStepResultValidator : ExtensionPoint {
    fun supports(flowType: String, stepId: String, agentName: String): Boolean = false

    fun validate(context: StepValidationContext): StepValidationVerdict =
        StepValidationVerdict.Accepted()
}
