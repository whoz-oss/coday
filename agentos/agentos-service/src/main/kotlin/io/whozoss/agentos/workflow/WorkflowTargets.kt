package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * One target produced by step 1 of a [Workflow]: a user to start a case for, and optionally the
 * business entity the case is about.
 *
 * @param userExternalId The user's [io.whozoss.agentos.user.User.externalId] (handover D4).
 * @param entityId Business entity id (talent, task, dossier…). Absent for the user-group workflow.
 * @param entityType Business entity type. Required together with [entityId] to give the case an
 *   `activeContext`.
 */
data class WorkflowTarget(
    val userExternalId: String,
    val entityId: String? = null,
    val entityType: String? = null,
)

/**
 * The contract every target-search tool (step 1) honours in its structured output, so that step 2
 * needs no knowledge of which tool produced it (handover D4):
 *
 * ```json
 * { "targets": [ { "userExternalId": "…", "entityId": "…", "entityType": "TALENT" } ] }
 * ```
 *
 * Tools may add other fields next to `targets`; they are ignored by the workflow. One case is started
 * per element, without deduplication.
 */
object WorkflowTargets {
    /** Name of the field carrying the targets, in step-1 output and in step-2 input alike. */
    const val FIELD = "targets"

    private val mapper = jacksonObjectMapper()

    /** JSON Schema of the `targets` array, for tools to embed in their `outputSchema()` / `inputSchema`. */
    val ARRAY_SCHEMA: JsonNode =
        mapper.readTree(
            """
            {
              "type": "array",
              "description": "Users to start a case for, optionally with the business entity the case is about.",
              "items": {
                "type": "object",
                "properties": {
                  "userExternalId": { "type": "string" },
                  "entityId":       { "type": "string" },
                  "entityType":     { "type": "string" }
                },
                "required": ["userExternalId"],
                "additionalProperties": true
              }
            }
            """.trimIndent(),
        )

    /** The structured output a step-1 tool returns: `{ "targets": [...] }` plus optional [extra] fields. */
    fun toStructuredOutput(
        targets: List<WorkflowTarget>,
        extra: Map<String, Any?> = emptyMap(),
    ): JsonNode = mapper.valueToTree(extra + (FIELD to targets))
}
