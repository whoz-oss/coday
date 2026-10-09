package io.whozoss.factory.workflow.domain

/**
 * Pure DAG evaluation of a declarative session (W8.3).
 *
 * The sequencer is the ONLY writer of the step lifecycle
 * `pending -> ready -> running -> completed | failed | blocked | waiting_human`.
 * This object carries the pure rules; the durable orchestration lives in
 * `io.whozoss.factory.workflow.service.SessionRunService`.
 *
 * The failure rule is applied here, never in the declarative JSON (which has no
 * `retry`, `gate` or `onFailure`):
 *  - a `pending` step whose dependencies are ALL `completed` becomes `ready`;
 *  - a `pending` step with AT LEAST ONE dependency in `{failed, blocked, cancelled}`
 *    becomes `blocked` (which propagates transitively, since a `blocked` step
 *    blocks its own dependents);
 *  - a `human` step that requires a decision is `waiting_human` and suspends the
 *    whole session until the interaction is answered;
 *  - the session is `completed` iff every step completed; it is `failed` if any
 *    step failed or was blocked and no step can run any more. There is NO retry.
 */
object SessionSequencer {

    /** Terminal statuses that never transition again. */
    private val TERMINAL = setOf(WorkflowStatuses.COMPLETED, WorkflowStatuses.FAILED, WorkflowStatuses.CANCELLED)

    /** A dependency in one of these states can never satisfy a dependent. */
    private val BLOCKING = setOf(WorkflowStatuses.FAILED, WorkflowStatuses.BLOCKED, WorkflowStatuses.CANCELLED)

    /** The `pending -> ready | blocked` transitions an evaluation produces. */
    data class Evaluation(
        val ready: List<String>,
        val blocked: List<String>,
    ) {
        val isEmpty: Boolean get() = ready.isEmpty() && blocked.isEmpty()
    }

    /**
     * Initial step statuses of a fresh session: a step with no dependency is
     * `ready`, every other step starts `pending`.
     */
    fun initialStatuses(steps: List<WorkflowStepDefinition>): LinkedHashMap<String, String> {
        val statuses = LinkedHashMap<String, String>()
        for (step in steps) {
            statuses[step.id] = if (step.dependsOn.isEmpty()) WorkflowStatuses.READY else WorkflowStatuses.PENDING
        }
        return statuses
    }

    /**
     * Computes the `pending -> ready | blocked` transitions for the current
     * status map. A step is never both: a blocking dependency takes precedence
     * over a satisfied one.
     */
    fun evaluate(steps: List<WorkflowStepDefinition>, statuses: Map<String, String>): Evaluation {
        val ready = ArrayList<String>()
        val blocked = ArrayList<String>()
        for (step in steps) {
            if (statuses[step.id] != WorkflowStatuses.PENDING) continue
            val dependencyStatuses = step.dependsOn.map { statuses[it] }
            when {
                dependencyStatuses.any { it in BLOCKING } -> blocked.add(step.id)
                dependencyStatuses.all { it == WorkflowStatuses.COMPLETED } -> ready.add(step.id)
            }
        }
        return Evaluation(ready, blocked)
    }

    /** Ids of the steps currently `ready` to execute, in definition order. */
    fun readySteps(steps: List<WorkflowStepDefinition>, statuses: Map<String, String>): List<String> =
        steps.map { it.id }.filter { statuses[it] == WorkflowStatuses.READY }

    /**
     * The overall session status once no step is `ready`/`running` any more.
     *
     * `waiting_human` wins (the session is suspended, not terminal); then a step
     * blocked for missing research keeps the session NON-terminal
     * ([WorkflowStatuses.NEEDS_RESEARCH]) as long as no step genuinely failed —
     * the engine routes a Searcher and re-arms the step. Otherwise `completed`
     * iff every step completed; otherwise `failed` (a failed/blocked branch, or a
     * deadlock that can never make progress — both are failures).
     */
    fun terminalStatus(steps: List<WorkflowStepDefinition>, statuses: Map<String, String>): String {
        val values = steps.map { statuses[it.id] }
        if (values.any { it == WorkflowStatuses.WAITING_HUMAN }) return WorkflowStatuses.WAITING_HUMAN
        if (values.all { it == WorkflowStatuses.COMPLETED }) return WorkflowStatuses.COMPLETED
        if (values.any { it == WorkflowStatuses.NEEDS_RESEARCH } && values.none { it == WorkflowStatuses.FAILED }) {
            return WorkflowStatuses.NEEDS_RESEARCH
        }
        return WorkflowStatuses.FAILED
    }

    /** Whether an overall session status is terminal (no further run can progress). */
    fun isTerminal(status: String): Boolean = status in TERMINAL
}
