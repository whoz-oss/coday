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
private val DEFINITION_FIELDS = setOf("schemaVersion", "workflowType", "version", "title", "trustedExecution", "execution", "steps")
private val TRUSTED_EXECUTION_FIELDS = setOf("allowedPaths")

/** Allowed keys of the optional top-level `execution` block. */
private val EXECUTION_FIELDS = setOf("plugin")
private val STEP_FIELDS = setOf("id", "name", "responsibility", "dependsOn", "deliverables")
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

        // Optional execution-plugin selection (Factory Forge). Backward compatible:
        // a definition without an `execution` block never carries the key and keeps
        // its historical canonical hash.
        var execution: Map<String, Any?>? = null
        if (record.containsKey("execution") && record["execution"] != null) {
            val rawExecution = record["execution"]
            if (rawExecution !is Map<*, *>) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "execution")
            }
            val executionRecord = rawExecution.entries.associate { it.key.toString() to it.value }
            if (executionRecord.keys.any { it !in EXECUTION_FIELDS }) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "execution")
            }
            val (plugin, pluginError) = text(executionRecord["plugin"], "execution.plugin", safe = true, maximum = 128)
            if (pluginError != null) return WorkflowDefinitionValidation.Invalid(pluginError)
            execution = mapOf("plugin" to plugin!!)
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
            val rawDeliverables = step["deliverables"] ?: emptyList<Any?>()
            if (rawDeliverables !is List<*> || rawDeliverables.size > 20) {
                return failure(WorkflowDefinitionErrorCodes.INVALID_VALUE, "$base.deliverables")
            }
            val deliverables = ArrayList<String>()
            rawDeliverables.forEachIndexed { deliverableIndex, deliverableValue ->
                val (deliverable, deliverableError) =
                    text(deliverableValue, "$base.deliverables[$deliverableIndex]", maximum = 512)
                if (deliverableError != null) return WorkflowDefinitionValidation.Invalid(deliverableError)
                if (!deliverables.add(deliverable!!)) {
                    return failure(
                        WorkflowDefinitionErrorCodes.INVALID_VALUE,
                        "$base.deliverables[$deliverableIndex]",
                        mapOf("reason" to "duplicate_deliverable"),
                    )
                }
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
                    deliverables = deliverables,
                ),
            )
        }

        when (val graph = SessionDefinitionValidator.validateGraph(steps)) {
            is SessionDefinitionValidator.Result.Invalid ->
                return WorkflowDefinitionValidation.Invalid(graph.error)
            is SessionDefinitionValidator.Result.Valid -> Unit
        }

        val normalizedStepJsons = steps.map { step ->
            linkedMapOf<String, Any?>(
                "id" to step.id,
                "name" to step.name,
                "responsibility" to step.responsibility.toJson(),
                "dependsOn" to step.dependsOn,
            )
                .also { normalizedStep ->
                    if (step.deliverables.isNotEmpty()) normalizedStep["deliverables"] = step.deliverables
                }
        }
        val normalized = linkedMapOf<String, Any?>(
            "schemaVersion" to WORKFLOW_DEFINITION_SCHEMA_VERSION,
            "workflowType" to type,
            "version" to version,
            "title" to title,
        )
        if (trustedExecution != null) normalized["trustedExecution"] = trustedExecution
        if (execution != null) normalized["execution"] = execution
        normalized["steps"] = normalizedStepJsons

        return WorkflowDefinitionValidation.Valid(normalized, steps)
    }
}

/** The SHA-256 of the canonicalized definition JSON. */
fun hashWorkflowDefinition(definition: Any?): String = CanonicalHash.workflowDefinitionHash(definition)

/** The canonical JSON of the definition. */
fun canonicalizeWorkflowDefinition(definition: Any?): String = CanonicalHash.canonicalizeJson(definition)
