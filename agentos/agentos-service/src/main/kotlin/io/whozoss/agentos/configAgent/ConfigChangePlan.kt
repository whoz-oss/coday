package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** Configuration entities the config agent can read and change. */
enum class ConfigKind {
    AGENT,
    SKILL,
    PROMPT,
    INTEGRATION,
    AUTH_SETTING,
}

enum class ConfigAction {
    CREATE,
    UPDATE,
    ENABLE,
    DISABLE,
    DEPLOY,
    UNDEPLOY,
}

/** Who a change reaches, shown on the card. Information only: nothing is restricted by it. */
enum class ConfigAffects {
    CALLER_ONLY,
    NAMESPACE_MEMBERS,
    PLATFORM,
}

/** What a created agent is deployed to right after its creation. */
enum class ConfigDeployTarget {
    NAMESPACE,
    NONE,
}

/**
 * A plan frozen by `AGENTOS_CONFIG__propose`, stored in the `structuredOutput` of its
 * `ToolResponseEvent`. The server never applies anything else than this plan, and only when the
 * client sends back the same [planHash].
 *
 * @property order Refs in application order: a topological sort of the `$op` dependencies, with a
 *   fixed rank per kind and action as tie-breaker (credentials and integrations before the agents
 *   that use them, agents before their prompts, deployment last).
 */
data class ConfigChangePlan(
    val schema: String = ConfigAgentIntegration.PLAN_SCHEMA,
    val questionId: UUID,
    val caseId: UUID,
    val namespaceId: UUID,
    val recipientUserId: UUID,
    val agentName: String,
    val summary: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val order: List<String>,
    val operations: List<PlannedOperation>,
    val warnings: List<String> = emptyList(),
    val planHash: String = "",
) {
    fun operation(ref: String): PlannedOperation? = operations.firstOrNull { it.ref == ref }
}

/**
 * One frozen operation.
 *
 * - `CREATE`: [scope] and [value] (the DTO fields, `{"$op": "<ref>"}` standing for the id an
 *   earlier operation created), plus [deployTo] for an agent.
 * - `UPDATE`: [target] and [changes], merged by the server onto the full DTO it re-reads.
 * - `ENABLE`, `DISABLE`, `DEPLOY`, `UNDEPLOY`: [target] only.
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
    val dependsOn: List<String> = emptyList(),
    val affects: ConfigAffects,
)

data class PlannedTarget(
    val id: UUID,
    val name: String,
    val namespaceId: UUID?,
    val userId: UUID?,
)

data class PlannedScope(
    val namespaceId: UUID?,
    val userId: UUID?,
)

/**
 * One field of an `UPDATE`. [beforeHash] is the hash of the masked value the server read at
 * `propose` time (null for a secret path), so a value changed since then is reported as a conflict
 * instead of being overwritten.
 */
data class PlannedChange(
    val path: String,
    val after: JsonNode? = null,
    val remove: Boolean = false,
    val beforeHash: String? = null,
)

data class SecretSlot(
    val path: String,
    val label: String,
)
