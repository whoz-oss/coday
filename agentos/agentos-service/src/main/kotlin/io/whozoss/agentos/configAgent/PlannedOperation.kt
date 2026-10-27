package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode

/**
 * One frozen operation.
 *
 * - `CREATE`: [scope] and [value] (the DTO fields, `{"$op": "<ref>"}` standing for the id an
 *   earlier operation created), plus [deployTo] for an agent. A namespace has [value] only. An
 *   integration with [copyFrom] starts from that integration's real fields, read by the server at
 *   apply time and never shown to the agent, and [value] only overrides some of them.
 * - `UPDATE`: [target] and [changes], merged by the server onto the full DTO it re-reads.
 * - `ENABLE`, `DISABLE`: [target] only.
 * - `DEPLOY`, `UNDEPLOY`: [target], plus [scope] when the namespace is one the plan creates.
 *
 * [secrets] are paths whose values the user types into the card; they never come from the LLM.
 */
data class PlannedOperation(
    val ref: String,
    val kind: ConfigKind,
    val action: ConfigAction,
    val target: PlannedTarget? = null,
    val scope: PlannedScope? = null,
    val value: JsonNode? = null,
    val changes: List<PlannedChange> = emptyList(),
    val secrets: List<SecretSlot> = emptyList(),
    val deployTo: ConfigDeployTarget? = null,
    val copyFrom: PlannedTarget? = null,
    val dependsOn: List<String> = emptyList(),
    val affects: ConfigAffects,
)
