package io.whozoss.factory.adapter.agentos

/**
 * The strict verdict rules of the AgentOS execution adapter — a pure function
 * over the durable, ordered events of a case.
 *
 * Rules (enforced in this order):
 * - No quiescent/terminal `CaseStatusEvent` seen → returns **null** ("not yet"):
 *   `RUNNING` proves only that the execution started, never a success, and the
 *   caller keeps observing.
 * - `IDLE`:
 *   - an unanswered `QuestionEvent` (no matching `AnswerEvent.questionId`) →
 *     [AgentOsExecutionVerdict.WaitingHuman] with `questionRef = questionId`;
 *   - else → [AgentOsExecutionVerdict.Indeterminate] with reason
 *     [AGENT_NO_STRUCTURED_RESULT] — **never** [AgentOsExecutionVerdict.Succeeded].
 *     A raw agent `MessageEvent` (free text) is NOT an authoritative result:
 *     the only authoritative success of an agent step is a structured result
 *     submitted through the single-use capability channel
 *     (`POST /api/factory/agent-step-results`). The SSE verdict observes the
 *     lifecycle and explicit failures; it must never promote silence or prose
 *     into a success — no success by silence, no result from free text.
 * - `ERROR` → [AgentOsExecutionVerdict.Failed] (`AGENT_CASE_ERROR`).
 * - `KILLED` → [AgentOsExecutionVerdict.Failed] (`AGENT_CASE_KILLED`), or
 *   [AgentOsExecutionVerdict.Interrupted] when the kill was caller-initiated
 *   (signalled through [DerivationContext.interruptRequested]). Never `Succeeded`.
 * - Transient events never participate (filtered defensively at the top).
 *
 * The timeout / reconnection-budget [AgentOsExecutionVerdict.Indeterminate]
 * variants are produced by the observing client, not here — see
 * [OBSERVATION_TIMEOUT] / [RECONNECT_BUDGET_EXHAUSTED].
 */
object VerdictDeriver {

    const val IDLE_WITHOUT_OUTPUT = "Turn reached IDLE without structured output"

    /**
     * Verdict reason of an `IDLE` turn with no pending question and no
     * structured capability-backed result: a raw agent message is never an
     * authoritative success. Kept stable — observability tooling matches on it.
     */
    const val AGENT_NO_STRUCTURED_RESULT = "AGENT_NO_STRUCTURED_RESULT"
    const val OBSERVATION_TIMEOUT = "SSE observation timeout"
    const val HUMAN_WAIT_TIMEOUT = "HUMAN_WAIT_TIMEOUT"
    const val RECONNECT_BUDGET_EXHAUSTED = "SSE reconnection budget exhausted"
    const val NOT_QUIESCENT = "Case has not reached a terminal or quiescent status"

    const val AGENT_CASE_ERROR = "AGENT_CASE_ERROR"
    const val AGENT_CASE_KILLED = "AGENT_CASE_KILLED"

    data class DerivationContext(
        val caseId: String,
        val interruptRequested: Boolean = false,
        val interruptReason: String? = null,
    )

    /** A `QuestionEvent` still waiting for its `AnswerEvent`. */
    data class PendingQuestion(
        val questionRef: String,
        val questionText: String?,
        val questionType: String?,
        val options: List<String>,
    )

    /**
     * Derive a verdict from the durable events of a case, or return null when
     * the case is not yet in a quiescent/terminal status (caller keeps
     * observing).
     */
    fun derive(events: List<CaseEventView>, context: DerivationContext): AgentOsExecutionVerdict? {
        val durable = events.filter { !it.isTransient() }
        val status = durable.lastOrNull { it.type == CaseEventView.CASE_STATUS_EVENT }?.status
        if (status !in CaseEventView.QUIESCENT_STATUSES) return null
        val facts = turnFacts(context.caseId, durable, status!!)
        return when (status) {
            "KILLED" -> if (context.interruptRequested) {
                AgentOsExecutionVerdict.Interrupted(
                    context.interruptReason ?: "Interrupted by the Factory.",
                    facts,
                )
            } else {
                AgentOsExecutionVerdict.Failed(
                    AGENT_CASE_KILLED,
                    "Case ${context.caseId} was killed.",
                    facts,
                )
            }
            "ERROR" -> AgentOsExecutionVerdict.Failed(
                AGENT_CASE_ERROR,
                "Case ${context.caseId} ended in ERROR.",
                facts,
            )
            "IDLE" -> {
                val pending = lastUnansweredQuestion(durable)
                when {
                    pending != null -> AgentOsExecutionVerdict.WaitingHuman(
                        questionRef = pending.questionRef,
                        questionText = pending.questionText,
                        evidence = facts + mapOf(
                            "questionRef" to pending.questionRef,
                            "question" to pending.questionText,
                            "questionType" to pending.questionType,
                            "options" to pending.options,
                        ),
                    )
                    else -> {
                        // No pending question: the turn is over, but a raw
                        // agent message is NOT an authoritative result. Only a
                        // structured submission through the capability channel
                        // can make an agent step succeed — this verdict stays
                        // non-authoritative (Indeterminate), carrying the free
                        // text as observation evidence only.
                        val summary = lastAgentMessage(durable)
                        AgentOsExecutionVerdict.Indeterminate(
                            AGENT_NO_STRUCTURED_RESULT,
                            if (summary.isNullOrBlank()) facts else facts + ("summary" to summary),
                        )
                    }
                }
            }
            // Any other status (RUNNING, PENDING, unexpected values) is not a verdict.
            else -> null
        }
    }

    /** The last `QuestionEvent` with no matching `AnswerEvent`, if any. */
    fun lastUnansweredQuestion(events: List<CaseEventView>): PendingQuestion? {
        val answered = events.mapNotNull { it.answeredQuestionId }.toSet()
        return events
            .filter { it.type == CaseEventView.QUESTION_EVENT && it.eventId !in answered }
            .lastOrNull()
            ?.let {
                PendingQuestion(
                    questionRef = it.eventId,
                    questionText = it.questionText,
                    questionType = it.questionType,
                    options = it.questionOptions,
                )
            }
    }

    /** The flattened text of the last agent `MessageEvent`, null when absent/blank-less. */
    fun lastAgentMessage(events: List<CaseEventView>): String? =
        events.lastOrNull { it.isAgentMessage() }?.messageText()

    /** Evidence facts — mirrors the `turnFacts` shape of `HttpAgentOsProxyClient`. */
    fun turnFacts(caseId: String, events: List<CaseEventView>, caseStatus: String): Map<String, Any?> = mapOf(
        "caseId" to caseId,
        "caseStatus" to caseStatus,
        "agentTurns" to events.count { it.type == CaseEventView.AGENT_FINISHED_EVENT },
        "toolCalls" to events.count { it.type == CaseEventView.TOOL_RESPONSE_EVENT },
        // AgentOS events do not carry a change list; durable modified-file
        // facts come from the worker's step-result binding.
        "modifiedFiles" to emptyList<String>(),
    )
}
