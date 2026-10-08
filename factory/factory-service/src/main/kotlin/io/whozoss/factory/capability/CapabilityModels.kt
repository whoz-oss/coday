package io.whozoss.factory.capability

import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import java.nio.file.Path

/**
 * Capability resolution of a declarative session step (W8.2).
 *
 * A step is resolved by `(kind, name)`:
 *  - `kind=agent` -> persona; the "launch an agent turn" capability is wired in
 *    W8.3 over HTTP AgentOS, so W8.2 returns [CapabilityOutcome.AgentDeferred];
 *  - `kind=code`  -> a verification declared in the *destination* repository
 *    (`factory/verification.json`), executed by `factory-verification-core`;
 *  - `kind=human` -> a human role; the checkpoint goes through
 *    `human_interactions`.
 */

/** Stable marker returned for an `agent` step whose transport is not wired yet. */
const val AGENT_NOT_IMPLEMENTED_YET = "NOT_IMPLEMENTED_YET"

/** A step to resolve plus the destination repository it runs against. */
data class CapabilityStepContext(
    val step: WorkflowStepDefinition,
    val repoRoot: Path,
)

/** Result of resolving (and, for `code`, executing) one step. */
sealed interface CapabilityOutcome {

    /** A declared code verification ran; the verdict is `exitCode == 0` and not timed out. */
    data class CodeExecuted(
        val stepId: String,
        val verificationName: String,
        val command: String,
        val timeoutMs: Long,
        val exitCode: Int,
        val timedOut: Boolean,
        val durationMs: Long,
        val stdout: String,
        val stderr: String,
    ) : CapabilityOutcome {
        val verdict: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * A code step could not be executed: undeclared in the whitelist
     * (`VERIFICATION_NOT_DECLARED`), or the target manifest is missing/invalid.
     * Nothing was spawned.
     */
    data class CodeRefused(
        val stepId: String,
        val verificationName: String?,
        val code: String,
        val message: String,
    ) : CapabilityOutcome

    /** The agent-turn capability is not implemented in W8.2 (see W8.3). */
    data class AgentDeferred(
        val stepId: String,
        val persona: String?,
        val code: String = AGENT_NOT_IMPLEMENTED_YET,
        val message: String = "Agent turn execution is scheduled for W8.3",
    ) : CapabilityOutcome

    /** An agent turn actually ran (only reachable once W8.3 wires the HTTP capability). */
    data class AgentCompleted(
        val stepId: String,
        val persona: String?,
        val status: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : CapabilityOutcome

    /**
     * An agent turn failed explicitly: AgentOS unreachable or misconfigured, a
     * timeout, a killed case or a case error. The verdict is a failure, never a
     * silent success.
     */
    data class AgentFailed(
        val stepId: String,
        val persona: String?,
        val code: String,
        val message: String,
        val facts: Map<String, Any?> = emptyMap(),
    ) : CapabilityOutcome

    /**
     * The worker proved (through the authoritative result channel) that the step
     * is BLOCKED for missing research. The proof (summary / findings / artifacts /
     * claims) is preserved, the dependants are NOT launched and the step is NEVER
     * converted into a terminal `FAIL`. The engine routes the step to a Searcher
     * attempt, then re-arms it as a brand-new attempt on the same worktree.
     */
    data class AgentNeedsResearch(
        val stepId: String,
        val persona: String?,
        val attemptId: String?,
        val resultId: String?,
        val summary: String,
        val findings: List<Any?> = emptyList(),
        val artifacts: List<Any?> = emptyList(),
        val claims: Map<String, Any?> = emptyMap(),
    ) : CapabilityOutcome

    /** A human checkpoint is required for this step; the projection/instance opens it. */
    data class HumanCheckpointRequired(
        val stepId: String,
        val role: String?,
    ) : CapabilityOutcome
}
