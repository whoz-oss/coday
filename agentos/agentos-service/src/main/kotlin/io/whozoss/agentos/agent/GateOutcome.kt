package io.whozoss.agentos.agent

import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent

/**
 * Outcome of [AgentAdvanced.handleConfirmationGate] and [AgentAdvanced.handleToolExecution].
 *
 * - [AwaitingConfirmation]: a [PendingConfirmationEvent] was emitted and the agent must
 *   exit the intention loop to wait for the user's reply.
 * - [ContinueLoop]: the tool was executed (or skipped) and the loop should proceed normally.
 */
internal enum class GateOutcome {
    AwaitingConfirmation,
    ContinueLoop,
}
