package io.whozoss.factory.workflow.domain

/**
 * Read model of the *governed* workflow actions and blockers.
 *
 * The `GET /api/factory/workflows/{workflowId}/actions` endpoint is the single
 * authority a client (e.g. the Cockpit) may use to decide what a workflow
 * permits right now: it never recomputes the state machine. Every action is
 * derived purely from existing state (projection steps, open human
 * interactions, durable agent attempts, the real-cost aggregate) and is only
 * emitted when the current state allows it. Each action carries the
 * [AllowedActionDto.expectedRevision] a subsequent command must fence on.
 *
 * No execution secret ever crosses this boundary: the DTOs below are a bounded
 * projection of stable identity + lifecycle fields only.
 */

/** Stable `type` values of an [AllowedActionDto]. */
object WorkflowActionTypes {
    const val REPLY = "reply"
    const val RETRY = "retry"
    const val CANCEL_ATTEMPT = "cancel_attempt"
    const val CONTINUE_COST = "continue_cost"
    const val STOP_COST = "stop_cost"
}

/** Stable `code` values of a [WorkflowBlockerDto]. */
object WorkflowBlockerCodes {
    const val WAITING_HUMAN_INTERACTION = "WAITING_HUMAN_INTERACTION"
    const val STEP_BLOCKED = "STEP_BLOCKED"
    const val ATTEMPT_FAILED = "ATTEMPT_FAILED"
    const val REAL_COST_PAUSED = "REAL_COST_PAUSED"
    const val VERIFICATION_FAILED = "VERIFICATION_FAILED"
    const val UNKNOWN_RUNTIME = "UNKNOWN_RUNTIME"
}

/**
 * One action the caller is authorized to execute from the current state.
 *
 * The target identity fields are nullable and only populated when applicable
 * to [type]:
 *  * `reply` -> [interactionId] (+ [stepId], [questionEventId] when projected),
 *  * `retry` -> [stepId],
 *  * `cancel_attempt` -> [attemptId] (+ [stepId], [caseId]),
 *  * `continue_cost` / `stop_cost` -> [caseId].
 *
 * [expectedRevision] is the revision the executing command must carry: the
 * interaction revision for `reply`, the attempt revision for `cancel_attempt`
 * and the current workflow revision for the workflow-fenced commands.
 */
data class AllowedActionDto(
    val type: String,
    val interactionId: String? = null,
    val stepId: String? = null,
    val attemptId: String? = null,
    val caseId: String? = null,
    val questionEventId: String? = null,
    val expectedRevision: Int,
    val label: String? = null,
)

/**
 * One active blocker that prevents a workflow from progressing unattended.
 *
 * [code] is a stable machine code (see [WorkflowBlockerCodes]); [stepId] points
 * at the target step when the blocker is step-scoped.
 */
data class WorkflowBlockerDto(
    val code: String,
    val stepId: String? = null,
    val message: String,
)

/** Enveloped payload of the authoritative actions/blockers read. */
data class WorkflowActionsResponseDto(
    val allowedActions: List<AllowedActionDto>,
    val blockers: List<WorkflowBlockerDto>,
)

/**
 * Optional command body of the cost-control pass-through endpoints.
 *
 * Identity and namespace are NEVER read from it: the caller's [TrustContext]
 * and the persisted workflow state are authoritative. `expectedThreshold` and
 * `expectedRevision` are optional command preconditions relayed verbatim;
 * `caseId` is only honoured when it is one of the workflow's persisted case
 * ids.
 */
data class WorkflowCostControlRequestDto(
    val expectedThreshold: Double? = null,
    val caseId: String? = null,
    val namespaceId: String? = null,
    val expectedRevision: Int? = null,
)
