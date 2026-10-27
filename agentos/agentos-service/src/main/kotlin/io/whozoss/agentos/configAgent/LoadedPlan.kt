package io.whozoss.agentos.configAgent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent

/** A frozen plan read back from the case history, with the question it announced. */
data class LoadedPlan(
    val plan: ConfigChangePlan,
    val question: QuestionEvent,
    /** Position of the plan's `ToolResponseEvent` in [events]. */
    val planIndex: Int,
    val events: List<CaseEvent>,
)
