package io.whozoss.factory.workflow.domain

/**
 * Optional step-level declaration: the step may append steps to the run that executes it.
 *
 * The declaration lives in the (immutable) definition; the appended steps live ONLY on the run
 * (see `WorkflowExpansionService`). Only `mode: "append"` exists. A definition without the field
 * keeps its historical normalized shape and canonical hash.
 */
data class WorkflowStepExpansion(val mode: String) {
    fun toJson(): Map<String, Any?> = linkedMapOf("mode" to mode)

    companion object {
        const val MODE_APPEND = "append"

        /** Strict parser shared by definition ingestion and trusted projection reconstruction. */
        fun parse(value: Any?): WorkflowStepExpansion? {
            val raw = value as? Map<*, *> ?: return null
            if (raw.keys.map { it.toString() }.toSet() != setOf("mode")) return null
            return if (raw["mode"] == MODE_APPEND) WorkflowStepExpansion(MODE_APPEND) else null
        }
    }
}

/** Stable violation codes of a rejected continuation (`details.violations[].code`). */
object WorkflowContinuationCodes {
    const val INVALID_CONTINUATION = "INVALID_CONTINUATION"
    const val CONTINUATION_NOT_ALLOWED = "CONTINUATION_NOT_ALLOWED"
    const val CONTINUATION_REQUIRED = "CONTINUATION_REQUIRED"
    const val STEP_ID_COLLISION = "STEP_ID_COLLISION"
    const val INVALID_DEPENDENCY = "INVALID_DEPENDENCY"
    const val UNSUPPORTED_IN_CONTINUATION = "UNSUPPORTED_IN_CONTINUATION"
    const val MAX_ITEMS = "MAX_ITEMS"
    const val AGENT_NOT_AVAILABLE = "AGENT_NOT_AVAILABLE"

    /** The namespace agent list could not be read: the continuation is refused (fail-closed), the agent can send it again. */
    const val AGENTS_UNVERIFIABLE = "AGENTS_UNVERIFIABLE"
}

/** A schema-location diagnostic: never carries a submitted value. */
data class ContinuationViolation(val path: String, val code: String, val message: String)

sealed interface ContinuationValidation {
    data class Valid(
        /**
         * Normalized steps added to the run: the `continuation-approval` step first, then the continuation steps
         * (their first steps already rewired on the approval).
         */
        val normalizedSteps: List<Map<String, Any?>>,
        val steps: List<WorkflowStepDefinition>,
        val stepIds: List<String>,
        /** Base definition + added steps, base steps untouched (the expander keeps its `expansion`). */
        val effectiveDefinition: Map<String, Any?>,
        val effectiveDefinitionHash: String,
    ) : ContinuationValidation

    data class Invalid(val violations: List<ContinuationViolation>) : ContinuationValidation
}

/**
 * Pure validation of a continuation (steps added at run time by an expanding step).
 *
 * The continuation is merged into the base definition and the merged definition goes through the
 * canonical [WorkflowDefinitionValidator], so graph, contract and routing rules have a single
 * implementation. Diagnostics are mapped back to `result.continuation.steps[i]...` locations.
 *
 * Trust boundary (the agent writes these steps): Factory always inserts a mandatory human step
 * [APPROVAL_STEP_ID] right after the expanding step, and every first step of the continuation depends on it, so
 * nothing an agent wrote runs before a person approved it. `code` steps are refused, and an unreadable agent list
 * refuses the continuation (fail-closed).
 */
object WorkflowContinuationValidator {
    const val MAX_STEPS = 50

    /** Id of the human approval step Factory inserts before the added steps. It is not counted in [MAX_STEPS]. */
    const val APPROVAL_STEP_ID = "continuation-approval"
    private const val ROOT = "result.continuation"
    private val INDEXED_PATH = Regex("^steps\\[(\\d+)](.*)$")
    private val DEPENDS_ON_PATH = Regex("^steps\\.(.+)\\.dependsOn$")

    private fun asStringMap(value: Any?): Map<String, Any?>? =
        (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }

    private fun approvalStep(expanderStepId: String): Map<String, Any?> = linkedMapOf(
        "id" to APPROVAL_STEP_ID,
        "name" to "Approve the generated steps",
        "responsibility" to linkedMapOf("kind" to "human", "name" to "Reviewer"),
        "dependsOn" to listOf(expanderStepId),
    )

    /**
     * [verifyAgents] is `true` when a continuation is submitted (an unreadable agent list, [availableAgents] `null`,
     * is then refused with `AGENTS_UNVERIFIABLE`). It is `false` when the already accepted continuation is added to
     * the run: the workspace precheck re-checks the agents of the full step list right after the addition.
     */
    fun validate(
        baseDefinition: Map<String, Any?>,
        expanderStepId: String,
        rawContinuation: Any?,
        availableAgents: List<String>?,
        verifyAgents: Boolean = true,
    ): ContinuationValidation {
        val baseSteps = (baseDefinition["steps"] as? List<*>).orEmpty().mapNotNull { asStringMap(it) }
        val expanderIndex = baseSteps.indexOfFirst { it["id"] == expanderStepId }
        if (expanderIndex < 0) {
            return invalid(WorkflowContinuationCodes.CONTINUATION_NOT_ALLOWED, ROOT, "the step is not part of the definition")
        }
        val continuation = asStringMap(rawContinuation)
        val rawSteps = continuation?.get("steps") as? List<*>
        if (continuation == null || continuation.keys.any { it != "steps" } || rawSteps == null) {
            return invalid(WorkflowContinuationCodes.INVALID_CONTINUATION, ROOT, "must be an object with a steps array")
        }
        if (rawSteps.isEmpty()) {
            return invalid(WorkflowContinuationCodes.INVALID_CONTINUATION, "$ROOT.steps", "must contain at least one step")
        }
        if (rawSteps.size > MAX_STEPS) {
            return invalid(WorkflowContinuationCodes.MAX_ITEMS, "$ROOT.steps", "must contain at most $MAX_STEPS steps")
        }

        val violations = ArrayList<ContinuationViolation>()
        fun violation(path: String, code: String, message: String) {
            violations += ContinuationViolation(path, code, message)
        }
        val baseIds = baseSteps.mapNotNull { it["id"] as? String }.toSet()
        val wired = ArrayList<Map<String, Any?>>()
        rawSteps.forEachIndexed { index, raw ->
            val path = "$ROOT.steps[$index]"
            val step = asStringMap(raw)
            if (step == null) {
                violation(path, WorkflowContinuationCodes.INVALID_CONTINUATION, "must be an object")
                return@forEachIndexed
            }
            if (step.containsKey("expansion") || step.containsKey("outcomeTransitions")) {
                violation(path, WorkflowContinuationCodes.UNSUPPORTED_IN_CONTINUATION, "declares a field that is not supported in a continuation")
            }
            // A run that discovers mid-way that it needs a worktree is not supported (reason: code_step).
            if (asStringMap(step["responsibility"])?.get("kind") == "code") {
                violation(
                    "$path.responsibility.kind",
                    WorkflowContinuationCodes.UNSUPPORTED_IN_CONTINUATION,
                    "a code step is not supported in a continuation (code_step)",
                )
            }
            val id = step["id"] as? String
            if (id != null && id in baseIds) {
                violation("$path.id", WorkflowContinuationCodes.STEP_ID_COLLISION, "collides with a step of the base definition")
            } else if (id == APPROVAL_STEP_ID) {
                violation("$path.id", WorkflowContinuationCodes.STEP_ID_COLLISION, "collides with the approval step Factory inserts")
            }
            val dependencies = step["dependsOn"] as? List<*>
            var dependsOn: Any? = step["dependsOn"]
            if (dependencies != null) {
                dependencies.forEachIndexed { dependencyIndex, dependency ->
                    if (dependency is String && dependency in baseIds && dependency != expanderStepId) {
                        violation(
                            "$path.dependsOn[$dependencyIndex]",
                            WorkflowContinuationCodes.INVALID_DEPENDENCY,
                            "must reference the expanding step or another continuation step",
                        )
                    }
                }
                // An empty dependsOn means "after the expanding step", and nothing runs before the approval:
                // a dependency on the expanding step becomes a dependency on the approval step.
                dependsOn = if (dependencies.isEmpty()) {
                    listOf(APPROVAL_STEP_ID)
                } else {
                    dependencies.map { if (it == expanderStepId) APPROVAL_STEP_ID else it }
                }
            }
            wired += step + ("dependsOn" to dependsOn)
        }
        if (violations.isNotEmpty()) return ContinuationValidation.Invalid(violations)

        // The expander legitimately gains a dependent (the approval): validate the merged graph without its declaration.
        val validationSteps = baseSteps.mapIndexed { index, step ->
            if (index == expanderIndex) step - "expansion" else step
        } + approvalStep(expanderStepId) + wired
        val forValidation = LinkedHashMap<String, Any?>(baseDefinition)
        forValidation["steps"] = validationSteps
        val valid = when (val result = WorkflowDefinitionValidator.validate(forValidation)) {
            is WorkflowDefinitionValidation.Invalid ->
                return ContinuationValidation.Invalid(listOf(mapError(result.error, baseSteps.size, wired)))
            is WorkflowDefinitionValidation.Valid -> result
        }
        val normalizedAll = (valid.definition["steps"] as? List<*>).orEmpty().mapNotNull { asStringMap(it) }
        // The approval step comes first, then the continuation steps.
        val addedNormalized = normalizedAll.drop(baseSteps.size)
        val addedSteps = valid.steps.drop(baseSteps.size)

        if (verifyAgents) {
            if (availableAgents == null) {
                return invalid(
                    WorkflowContinuationCodes.AGENTS_UNVERIFIABLE,
                    ROOT,
                    "the agents of the namespace could not be verified; send the result again",
                )
            }
            addedSteps.drop(1).forEachIndexed { index, step ->
                if (step.responsibility.kind == ResponsibilityKind.AGENT) {
                    val name = step.responsibility.name
                    if (name == null || name !in availableAgents) {
                        violation(
                            "$ROOT.steps[$index].responsibility.name",
                            WorkflowContinuationCodes.AGENT_NOT_AVAILABLE,
                            "is not an agent enabled in the namespace",
                        )
                    }
                }
            }
            if (violations.isNotEmpty()) return ContinuationValidation.Invalid(violations)
        }

        val effective = LinkedHashMap<String, Any?>(baseDefinition)
        effective["steps"] = baseSteps + addedNormalized
        return ContinuationValidation.Valid(
            normalizedSteps = addedNormalized,
            steps = addedSteps,
            stepIds = addedSteps.map { it.id },
            effectiveDefinition = effective,
            effectiveDefinitionHash = hashWorkflowDefinition(effective),
        )
    }

    private fun invalid(code: String, path: String, message: String) =
        ContinuationValidation.Invalid(listOf(ContinuationViolation(path, code, message)))

    /** Maps a merged-definition error back to a continuation location, without echoing any submitted value. */
    private fun mapError(error: WorkflowDefinitionError, baseCount: Int, wired: List<Map<String, Any?>>): ContinuationViolation {
        val reason = error.details["reason"] as? String
        val message = if (reason != null) "is invalid ($reason)" else "is invalid"
        val indexed = INDEXED_PATH.find(error.path)
        val byId = DEPENDS_ON_PATH.find(error.path)
        val path = when {
            indexed != null -> {
                val position = indexed.groupValues[1].toInt()
                // Position `baseCount` is the approval step Factory inserts; the continuation steps follow it.
                val continuationIndex = position - baseCount - 1
                when {
                    position < baseCount -> "definition.steps[$position]${indexed.groupValues[2]}"
                    continuationIndex < 0 -> ROOT
                    else -> "$ROOT.steps[$continuationIndex]${indexed.groupValues[2]}"
                }
            }
            byId != null -> {
                val index = wired.indexOfFirst { it["id"] == byId.groupValues[1] }
                if (index >= 0) "$ROOT.steps[$index].dependsOn" else "$ROOT.steps"
            }
            error.path == "steps" || error.path.startsWith("steps.") -> "$ROOT.steps"
            else -> ROOT
        }
        return ContinuationViolation(path, error.code, message)
    }
}
