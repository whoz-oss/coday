package io.whozoss.factory.workflow.domain

/**
 * Pure workflow-definition validation + canonical hash.
 *
 * Faithful port of `factory/src/domain/workflow/workflow-definition.ts`. The
 * validator is dependency-free and deterministic; the hash is the SHA-256 of
 * the recursively key-sorted normalized definition (see [CanonicalHash]).
 */
object WorkflowDefinitionErrorCodes {
    const val INVALID_DEFINITION = "INVALID_DEFINITION"
    const val INVALID_SCHEMA_VERSION = "INVALID_SCHEMA_VERSION"
    const val INVALID_VALUE = "INVALID_VALUE"
    const val DUPLICATE_STEP_ID = "DUPLICATE_STEP_ID"
    const val MISSING_DEPENDENCY = "MISSING_DEPENDENCY"
    const val SELF_DEPENDENCY = "SELF_DEPENDENCY"
    const val DEPENDENCY_CYCLE = "DEPENDENCY_CYCLE"
    const val INVALID_RESPONSIBILITY = "INVALID_RESPONSIBILITY"
}

/** A validation failure carrying the stable machine code + JSON path. */
data class WorkflowDefinitionError(
    val code: String,
    val path: String,
    val details: Map<String, Any?> = emptyMap(),
)

/** Result of [validateWorkflowDefinition]. */
sealed interface WorkflowDefinitionValidation {
    data class Valid(val definition: Map<String, Any?>, val steps: List<WorkflowStepDefinition>) : WorkflowDefinitionValidation
    data class Invalid(val error: WorkflowDefinitionError) : WorkflowDefinitionValidation
}

private val SAFE_ID = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$")
private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
private val DEFINITION_FIELDS = setOf("schemaVersion", "workflowType", "version", "title", "trustedExecution", "steps")
private val TRUSTED_EXECUTION_FIELDS = setOf("allowedPaths")
private val STEP_FIELDS = setOf("id", "name", "responsibility", "dependsOn")
private val RESPONSIBILITY_FIELDS = setOf("kind", "name")

object WorkflowDefinitionValidator {

    private fun failure(code: String, path: String, details: Map<String, Any?> = emptyMap()) =
        WorkflowDefinitionValidation.Invalid(WorkflowDefinitionError(code, path, details))

    private fun text(value: Any?, path: String, safe: Boolean = false, maximum: Int = 256): Pair<String?, WorkflowDefinitionError?> {
        if (value !is String || value.isBlank() || value.length > maximum || (safe && !SAFE_ID.matches(value))) {
            return null to WorkflowDefinitionError(WorkflowDefinitionErrorCodes.INVALID_VALUE, path)
        }
        return value to null
    }

    /** Validates + normalizes an unknown definition payload. */
    fun validate(input: Any?): WorkflowDefinitionValidation {
        if (input !is Map<*, *>) {
            return failure(WorkflowDefinitionErrorCodes.INVALID_DEFINITION, "$")
        }
        val record = input.entries.associate { it.key.toString() to it.value }
        if (record.keys.any { it !in DEFINITION_FIELDS }) {
            return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "$", mapOf("reason" to "unknown_field"))
        }
        if (record["schemaVersion"] != WORKFLOW_DEFINITION_SCHEMA_VERSION) {
            return failure(WorkflowDefinitionErrorCodes.INVALID_SCHEMA_VERSION, "schemaVersion")
        }
        val (type, typeError) = text(record["workflowType"], "workflowType", safe = true)
        if (typeError != null) return WorkflowDefinitionValidation.Invalid(typeError)
        val version = record["version"]
        if (version !is String || !SEMVER.matches(version)) {
            return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "version")
        }
        val (title, titleError) = text(record["title"], "title")
        if (titleError != null) return WorkflowDefinitionValidation.Invalid(titleError)

        var trustedExecution: Map<String, Any?>? = null
        if (record.containsKey("trustedExecution") && record["trustedExecution"] != null) {
            val rawTrusted = record["trustedExecution"]
            if (rawTrusted !is Map<*, *>) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "trustedExecution")
            }
            val trustedRecord = rawTrusted.entries.associate { it.key.toString() to it.value }
            val allowedPaths = trustedRecord["allowedPaths"]
            if (trustedRecord.keys.any { it !in TRUSTED_EXECUTION_FIELDS } ||
                allowedPaths !is List<*> || allowedPaths.isEmpty()
            ) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "trustedExecution")
            }
            val normalizedPaths = ArrayList<String>()
            allowedPaths.forEachIndexed { index, path ->
                if (path !is String || path.isEmpty() || path.startsWith("/") ||
                    path.contains('\\') || path.split('/').contains("..") || path.contains('\u0000')
                ) {
                    return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "trustedExecution.allowedPaths[$index]")
                }
                normalizedPaths.add(path)
            }
            trustedExecution = mapOf("allowedPaths" to normalizedPaths)
        }

        val rawSteps = record["steps"]
        if (rawSteps !is List<*> || rawSteps.isEmpty() || rawSteps.size > 500) {
            return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "steps")
        }
        val ids = LinkedHashSet<String>()
        val steps = ArrayList<WorkflowStepDefinition>()
        rawSteps.forEachIndexed { index, raw ->
            val base = "steps[$index]"
            if (raw !is Map<*, *>) return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, base)
            val step = raw.entries.associate { it.key.toString() to it.value }
            if (step.keys.any { it !in STEP_FIELDS }) return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, base)
            val (id, idError) = text(step["id"], "$base.id", safe = true)
            if (idError != null) return WorkflowDefinitionValidation.Invalid(idError)
            if (!ids.add(id!!)) {
                return failure(WorkflowDefinitionErrorCodes.DUPLICATE_STEP_ID, "$base.id", mapOf("stepId" to id))
            }
            val (name, nameError) = text(step["name"], "$base.name")
            if (nameError != null) return WorkflowDefinitionValidation.Invalid(nameError)
            val responsibility = step["responsibility"]
            if (responsibility !is Map<*, *>) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_RESPONSIBILITY, "$base.responsibility")
            }
            val responsibilityRecord = responsibility.entries.associate { it.key.toString() to it.value }
            if (responsibilityRecord.keys.any { it !in RESPONSIBILITY_FIELDS } ||
                ResponsibilityKind.fromWire(responsibilityRecord["kind"] as? String) == null
            ) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_RESPONSIBILITY, "$base.responsibility")
            }
            val (responsibilityName, responsibilityNameError) =
                text(responsibilityRecord["name"], "$base.responsibility.name")
            if (responsibilityNameError != null) return WorkflowDefinitionValidation.Invalid(responsibilityNameError)
            val rawDependencies = step["dependsOn"]
            if (rawDependencies !is List<*>) return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "$base.dependsOn")
            val dependencies = ArrayList<String>()
            val seen = LinkedHashSet<String>()
            rawDependencies.forEachIndexed { dependencyIndex, dependencyValue ->
                val (dependency, dependencyError) =
                    text(dependencyValue, "$base.dependsOn[$dependencyIndex]", safe = true)
                if (dependencyError != null) return WorkflowDefinitionValidation.Invalid(dependencyError)
                if (!seen.add(dependency!!)) {
                    return failure(
                        WorkflowDefinitionErrorCodes.INVALID_VALUE,
                        "$base.dependsOn[$dependencyIndex]",
                        mapOf("reason" to "duplicate_dependency"),
                    )
                }
                dependencies.add(dependency)
            }
            steps.add(
                WorkflowStepDefinition(
                    id = id,
                    name = name!!,
                    responsibility = WorkflowStepResponsibility(
                        kind = ResponsibilityKind.fromWire(responsibilityRecord["kind"] as String)!!,
                        name = responsibilityName,
                    ),
                    dependsOn = dependencies,
                ),
            )
        }

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
            if (cyclic(step.id)) return failure(WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE, "steps")
        }

        val normalizedStepJsons = steps.map { step ->
            linkedMapOf<String, Any?>(
                "id" to step.id,
                "name" to step.name,
                "responsibility" to step.responsibility.toJson(),
                "dependsOn" to step.dependsOn,
            )
        }
        val normalized = linkedMapOf<String, Any?>(
            "schemaVersion" to WORKFLOW_DEFINITION_SCHEMA_VERSION,
            "workflowType" to type,
            "version" to version,
            "title" to title,
        )
        if (trustedExecution != null) normalized["trustedExecution"] = trustedExecution
        normalized["steps"] = normalizedStepJsons

        return WorkflowDefinitionValidation.Valid(normalized, steps)
    }
}

/** The SHA-256 of the canonicalized definition JSON. */
fun hashWorkflowDefinition(definition: Any?): String = CanonicalHash.workflowDefinitionHash(definition)

/** The canonical JSON of the definition. */
fun canonicalizeWorkflowDefinition(definition: Any?): String = CanonicalHash.canonicalizeJson(definition)
