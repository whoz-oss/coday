package io.whozoss.factory.workflow.domain

/**
 * Declarative WorkflowProjection v1/v2 validation + canonicalization.
 *
 * Port of the contract in `factory/WORKFLOW_PROJECTION.md` and
 * `factory/lib/workflow-projection.mjs`: bounded, machine-safe identities, an
 * allowed status, and steps with a safe stable `id` and an allowed `status`.
 * Version 2 additionally requires every step to carry
 * `responsibility: { kind, name? }`. The optional `expectedRevision` command
 * precondition is a *command* field: it is removed from the normalized state and
 * therefore never affects canonical equality or the SHA-256 hash.
 */

private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val PROJECTION_FIELDS = setOf("schemaVersion", "workflowId", "workflowType", "title", "status", "steps")
// `lane`, `startedAt`, `completedAt` and `durationMs` are the optional
// multi-lane timeline attributes (W8.4) surfaced by the session projection for
// the cockpit swimlanes. They are pass-through metadata: absent for a v1/v2
// publication, present once a session run has scheduled/executed the step.
private val STEP_FIELDS =
    setOf("id", "name", "status", "description", "dependsOn", "responsibility", "lane", "startedAt", "completedAt", "durationMs")
private val LANE_VALUES = setOf("agent", "code", "human")

data class ProjectionError(val code: String, val path: String, val details: Map<String, Any?> = emptyMap())

sealed interface ProjectionValidation {
    data class Valid(val normalized: Map<String, Any?>) : ProjectionValidation
    data class Invalid(val error: ProjectionError) : ProjectionValidation
}

object WorkflowProjectionValidator {

    private fun invalid(code: String, path: String, details: Map<String, Any?> = emptyMap()) =
        ProjectionValidation.Invalid(ProjectionError(code, path, details))

    fun validate(input: Any?, expectedWorkflowId: String): ProjectionValidation {
        if (input !is Map<*, *>) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$")
        val record = input.entries.associate { it.key.toString() to it.value }
        if (record.keys.any { it !in PROJECTION_FIELDS }) {
            return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$", mapOf("reason" to "unknown_field"))
        }
        val schemaVersion = record["schemaVersion"] as? String
        if (schemaVersion !in setOf("1", "2")) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "schemaVersion")
        if (record["workflowId"] != expectedWorkflowId || !SAFE_ID.matches(expectedWorkflowId)) {
            return invalid(WorkflowErrorCodes.WORKFLOW_ID_MISMATCH, "workflowId")
        }
        val workflowType = record["workflowType"] as? String
        if (workflowType == null || !SAFE_ID.matches(workflowType)) {
            return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "workflowType")
        }
        val title = record["title"] as? String
        if (title == null || title.isBlank() || title.length > 512) {
            return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "title")
        }
        val status = record["status"] as? String
        if (!WorkflowStatuses.isKnown(status)) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "status")
        val rawSteps = record["steps"]
        if (rawSteps !is List<*> || rawSteps.isEmpty() || rawSteps.size > 500) {
            return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "steps")
        }
        val ids = LinkedHashSet<String>()
        val steps = ArrayList<Map<String, Any?>>()
        rawSteps.forEachIndexed { index, raw ->
            val base = "steps[$index]"
            if (raw !is Map<*, *>) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, base)
            val step = raw.entries.associate { it.key.toString() to it.value }
            if (step.keys.any { it !in STEP_FIELDS }) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, base)
            val id = step["id"] as? String
            if (id == null || !SAFE_ID.matches(id)) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.id")
            if (!ids.add(id)) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.id", mapOf("reason" to "duplicate"))
            val name = step["name"] as? String
            if (name == null || name.isBlank() || name.length > 512) {
                return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.name")
            }
            val stepStatus = step["status"] as? String
            if (!WorkflowStatuses.isKnown(stepStatus)) return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.status")
            val dependsOn = step["dependsOn"]
            if (dependsOn != null && (dependsOn !is List<*> || dependsOn.any { it !is String || !SAFE_ID.matches(it) })) {
                return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.dependsOn")
            }
            val lane = step["lane"]
            if (lane != null && (lane !is String || lane !in LANE_VALUES)) {
                return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.lane")
            }
            for (timestampField in listOf("startedAt", "completedAt")) {
                val timestamp = step[timestampField]
                if (timestamp != null && timestamp !is String) {
                    return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.$timestampField")
                }
            }
            val durationMs = step["durationMs"]
            if (durationMs != null && (durationMs !is Number || durationMs.toLong() < 0)) {
                return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.durationMs")
            }
            val responsibility = step["responsibility"]
            if (schemaVersion == "2") {
                if (responsibility !is Map<*, *>) {
                    return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.responsibility")
                }
                val responsibilityRecord = responsibility.entries.associate { it.key.toString() to it.value }
                if (responsibilityRecord.keys.any { it !in setOf("kind", "name") } ||
                    ResponsibilityKind.fromWire(responsibilityRecord["kind"] as? String) == null
                ) {
                    return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.responsibility")
                }
                val responsibilityName = responsibilityRecord["name"]
                if (responsibilityName != null && (responsibilityName !is String || responsibilityName.isBlank() ||
                        responsibilityName.length > 256)
                ) {
                    return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "$base.responsibility.name")
                }
            }
            val normalizedStep = LinkedHashMap<String, Any?>()
            normalizedStep["id"] = id
            normalizedStep["name"] = name
            normalizedStep["status"] = stepStatus
            (step["description"] as? String)?.let { normalizedStep["description"] = it }
            if (dependsOn != null) normalizedStep["dependsOn"] = dependsOn.map { it as String }
            if (responsibility is Map<*, *>) {
                val responsibilityRecord = responsibility.entries.associate { it.key.toString() to it.value }
                normalizedStep["responsibility"] = linkedMapOf<String, Any?>(
                    "kind" to (responsibilityRecord["kind"] as String),
                    "name" to responsibilityRecord["name"],
                ).filterValues { it != null }
            }
            // Optional multi-lane timeline attributes: explicit `lane` wins,
            // otherwise it is derived from the responsibility kind.
            val explicitLane = lane as? String
            val responsibilityKind = (responsibility as? Map<*, *>)?.get("kind") as? String
            val derivedLane = explicitLane
                ?: responsibilityKind?.takeIf { it in LANE_VALUES }
            if (derivedLane != null) normalizedStep["lane"] = derivedLane
            (step["startedAt"] as? String)?.let { normalizedStep["startedAt"] = it }
            (step["completedAt"] as? String)?.let { normalizedStep["completedAt"] = it }
            (durationMs as? Number)?.let { normalizedStep["durationMs"] = it.toLong() }
            steps.add(normalizedStep)
        }
        // Dependency targets must exist (array order stays semantic).
        val allIds = ids.toSet()
        steps.forEachIndexed { index, step ->
            (step["dependsOn"] as? List<*>)?.forEach { dependency ->
                if (dependency !in allIds) {
                    return invalid(WorkflowErrorCodes.INVALID_PROJECTION, "steps[$index].dependsOn", mapOf("target" to dependency))
                }
            }
        }
        val normalized = linkedMapOf<String, Any?>(
            "schemaVersion" to schemaVersion,
            "workflowId" to expectedWorkflowId,
            "workflowType" to workflowType,
            "title" to title,
            "status" to status,
            "steps" to steps,
        )
        return ProjectionValidation.Valid(normalized)
    }
}
