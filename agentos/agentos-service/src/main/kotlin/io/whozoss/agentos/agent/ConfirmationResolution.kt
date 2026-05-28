package io.whozoss.agentos.agent

/**
 * Outcome of [AgentAdvanced.handleConfirmationResolution].
 *
 * Discriminates four runtime situations so that [AgentAdvanced.run] can route each
 * to the correct post-resolution behaviour: close the turn cleanly vs fall through
 * to the normal intention loop.
 *
 * - [Unresolved]: the pending is still alive (no user reply yet, or an AMBIGUOUS
 *   re-ask was just emitted, or the run was cancelled mid-flight). Close the turn.
 * - [Applied]: the user confirmed AND the tool ran successfully. Fall through so
 *   the LLM can comment naturally on the outcome.
 * - [Rejected]: the user explicitly refused. Fall through so the LLM can produce
 *   a clarifying follow-up. The hardened `shouldConfirm` clauses prevent an
 *   autonomous retry of the same destructive intention without a fresh explicit
 *   prompt.
 * - [Aborted]: tool threw post-confirm OR the pending was orphan-closed (tool
 *   missing, etc.). Action did not apply AND there's nothing meaningful to invite
 *   the user to do next — close the turn cleanly.
 */
internal sealed interface ConfirmationResolution {
    data object Unresolved : ConfirmationResolution

    data object Applied : ConfirmationResolution

    data object Rejected : ConfirmationResolution

    data object Aborted : ConfirmationResolution
}
