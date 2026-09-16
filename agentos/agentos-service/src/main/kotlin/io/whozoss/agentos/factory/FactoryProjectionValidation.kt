package io.whozoss.agentos.factory

internal object FactoryProjectionValidation {
    data class Failure(val code: String, val field: String, val reason: String)

    val statuses = setOf("pending", "ready", "running", "waiting_human", "blocked", "completed", "failed", "cancelled")
    private val safeId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    const val maxText = 256
    const val maxDescription = 4096
    const val maxSteps = 500
    const val maxDependencies = 100

    fun validate(input: FactoryPublishProjectionTool.Input?): Failure? {
        if (input == null) return Failure("INVALID_PROJECTION", "$", "projection is required")
        if (input.schemaVersion != "1") return Failure("UNSUPPORTED_SCHEMA_VERSION", "schemaVersion", "must equal string '1'")
        if (!safeId.matches(input.workflowId)) return Failure("INVALID_PROJECTION", "workflowId", "must be a safe identifier of at most 128 characters")
        if (!bounded(input.workflowType, maxText)) return Failure("INVALID_PROJECTION", "workflowType", "must be non-blank and at most $maxText characters")
        if (!bounded(input.title, maxText)) return Failure("INVALID_PROJECTION", "title", "must be non-blank and at most $maxText characters")
        if (input.status !in statuses) return Failure("INVALID_PROJECTION", "status", "must be one of ${statuses.sorted().joinToString(",")}")
        if (input.expectedRevision?.let { it < 0 } == true) return Failure("INVALID_PROJECTION", "expectedRevision", "must be non-negative")
        if (input.steps.size > maxSteps) return Failure("INVALID_PROJECTION", "steps", "must contain at most $maxSteps steps")
        val ids = mutableSetOf<String>()
        input.steps.forEachIndexed { index, step ->
            val field = "steps[$index]"
            if (!safeId.matches(step.id)) return Failure("INVALID_PROJECTION", "$field.id", "must be a safe identifier of at most 128 characters")
            if (!ids.add(step.id)) return Failure("INVALID_PROJECTION", "$field.id", "must be unique")
            if (!bounded(step.name, maxText)) return Failure("INVALID_PROJECTION", "$field.name", "must be non-blank and at most $maxText characters")
            if (step.status !in statuses) return Failure("INVALID_PROJECTION", "$field.status", "must be one of ${statuses.sorted().joinToString(",")}")
            if (step.description?.let { !bounded(it, maxDescription) } == true) return Failure("INVALID_PROJECTION", "$field.description", "must be non-blank and at most $maxDescription characters")
            if (step.dependsOn.size > maxDependencies) return Failure("INVALID_PROJECTION", "$field.dependsOn", "must contain at most $maxDependencies dependencies")
            if (step.dependsOn.toSet().size != step.dependsOn.size) return Failure("INVALID_PROJECTION", "$field.dependsOn", "must not contain duplicates")
            if (step.dependsOn.any { !safeId.matches(it) }) return Failure("INVALID_PROJECTION", "$field.dependsOn", "must contain only safe identifiers")
        }
        input.steps.forEachIndexed { index, step ->
            if (step.id in step.dependsOn) return Failure("INVALID_PROJECTION", "steps[$index].dependsOn", "must not contain the step itself")
            val missing = step.dependsOn.firstOrNull { it !in ids }
            if (missing != null) return Failure("INVALID_PROJECTION", "steps[$index].dependsOn", "references unknown step '$missing'")
        }
        val graph = input.steps.associate { it.id to it.dependsOn }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun cycle(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            if (graph.getValue(id).any(::cycle)) return true
            visiting -= id
            visited += id
            return false
        }
        if (input.steps.any { cycle(it.id) }) return Failure("INVALID_PROJECTION", "steps.dependsOn", "must form an acyclic graph")
        return null
    }

    private fun bounded(value: String, maximum: Int) = value.isNotBlank() && value.length <= maximum
}
