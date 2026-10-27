package io.whozoss.agentos.configAgent

import java.time.Instant
import java.util.UUID

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
