package io.whozoss.agentos.configAgent

sealed interface PlanningResult {
    data class Planned(
        val plan: ConfigChangePlan,
    ) : PlanningResult

    /** One message per offending operation (prefixed by its ref), so the LLM can fix its call. */
    data class Invalid(
        val errors: List<String>,
    ) : PlanningResult
}
