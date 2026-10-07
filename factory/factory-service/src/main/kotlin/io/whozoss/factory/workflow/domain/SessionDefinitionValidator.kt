package io.whozoss.factory.workflow.domain

/**
 * Pure validation of the declarative session graph (W8.2).
 *
 * A SESSION is a list of STEPS with exactly `id`, `name`, `dependsOn` and
 * `responsibility { kind, name }`. No orchestration property (`gate`, `retry`,
 * `onFailure`, `briefTemplate`…) is ever modelled, so none can be declared: the
 * format is intentionally minimal and the smart orchestration stays in an
 * external Coday agent, never in the file.
 *
 * The JSON-shape validation (unknown fields, safe ids, string lengths,
 * `schemaVersion`…) lives in [WorkflowDefinitionValidator]. This validator
 * enforces the DAG rules on an already-deserialized, typed list of
 * [WorkflowStepDefinition]:
 *  - unique step ids;
 *  - every `dependsOn` target refers to an existing step id;
 *  - no self-dependency;
 *  - no cycle in the DAG.
 *
 * `kind` validity is carried by [ResponsibilityKind] at the type level: the
 * string-to-enum resolution (and the rejection of an unknown `kind`) is done by
 * [WorkflowDefinitionValidator] when parsing the payload.
 */
object SessionDefinitionValidator {

    sealed interface Result {
        data class Valid(val steps: List<WorkflowStepDefinition>) : Result
        data class Invalid(val error: WorkflowDefinitionError) : Result
    }

    private fun failure(code: String, path: String, details: Map<String, Any?> = emptyMap()) =
        Result.Invalid(WorkflowDefinitionError(code, path, details))

    /**
     * Validates a session's steps: id uniqueness plus the whole DAG. Returns the
     * steps unchanged on success, so the caller can keep using them.
     */
    fun validate(steps: List<WorkflowStepDefinition>): Result {
        if (steps.isEmpty()) return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "steps")
        val ids = LinkedHashSet<String>()
        for (step in steps) {
            if (step.id.isBlank()) return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "steps.id")
            if (!ids.add(step.id)) {
                return failure(
                    WorkflowDefinitionErrorCodes.DUPLICATE_STEP_ID,
                    "steps.${step.id}.id",
                    mapOf("stepId" to step.id),
                )
            }
        }
        return validateGraph(steps)
    }

    /**
     * Validates only the `dependsOn` DAG: existence of every target, no
     * self-dependency, acyclicity. Shared with [WorkflowDefinitionValidator] so
     * the DAG rules have a single implementation.
     */
    fun validateGraph(steps: List<WorkflowStepDefinition>): Result {
        val ids = steps.mapTo(LinkedHashSet()) { it.id }
        for (step in steps) {
            for (dependency in step.dependsOn) {
                if (dependency == step.id) {
                    return failure(WorkflowDefinitionErrorCodes.SELF_DEPENDENCY, "steps.${step.id}.dependsOn")
                }
                if (dependency !in ids) {
                    return failure(
                        WorkflowDefinitionErrorCodes.MISSING_DEPENDENCY,
                        "steps.${step.id}.dependsOn",
                        mapOf("target" to dependency),
                    )
                }
            }
        }

        val graph = steps.associate { it.id to it.dependsOn }
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun cyclic(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting.add(id)
            for (dependency in graph[id].orEmpty()) if (cyclic(dependency)) return true
            visiting.remove(id)
            visited.add(id)
            return false
        }
        for (step in steps) {
            if (cyclic(step.id)) {
                return failure(WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE, "steps")
            }
        }
        return Result.Valid(steps)
    }
}
