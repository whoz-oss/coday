package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.workflow.AgentLoopAct
import io.whozoss.agentos.workflow.AgentLoopPayload
import io.whozoss.agentos.workflow.AgentLoopSearch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ---------------------------------------------------------------------------
// Nested domain value objects
// ---------------------------------------------------------------------------

/**
 * Describes how often and at what time a [ScheduledPrompt] fires.
 *
 * [unit] is either WEEK or MONTH.
 * [days] is an optional filter on days of the week; only meaningful for WEEK
 * (ignored for MONTH). Empty list = fires on the same day-of-week as startDate.
 * [timeUtc] is the execution time in UTC.
 */
data class Recurrence(
    val unit: SchedulerUnit,
    val days: List<DayOfWeek> = emptyList(),
    val timeUtc: LocalTime,
)

/**
 * Describes the planning window of a [ScheduledPrompt].
 *
 * [startDate] is the earliest execution date.
 * [endType] controls termination:
 * - NEVER: runs indefinitely.
 * - ON_DATE: stops after [endDate] (required, strictly after [startDate]).
 * - OCCURRENCES: stops after [maxOccurrenceCount] executions (required, > 0).
 */
data class Planning(
    val startDate: LocalDate,
    val endType: SchedulerEndType = SchedulerEndType.NEVER,
    val endDate: LocalDate? = null,
    val maxOccurrenceCount: Int? = null,
)

/**
 * What a LOOP agent runs for a [ScheduledPrompt]: [searchTool] is called with [searchParams], and one case is
 * started with [agentName] for each result. The prompt of those cases is not stored here: it is the
 * scheduled prompt's own content, added by [toLoopPayload].
 */
data class AgentFlowConfig(
    val searchTool: String,
    val searchParams: JsonNode? = null,
    val agentName: String,
) {
    /** The full payload AgentLoop runs, `prompt` being the first message of each started case. */
    fun toLoopPayload(prompt: String): AgentLoopPayload =
        AgentLoopPayload(search = AgentLoopSearch(searchTool, searchParams), act = AgentLoopAct(agentName, prompt))
}

// ---------------------------------------------------------------------------
// Aggregate root
// ---------------------------------------------------------------------------

/**
 * Declares a periodic prompt run: which agent to invoke, on what recurrence, within
 * what planning window.
 *
 * ### Scope model
 *
 * | namespaceId | userId | Scope          | Priority   |
 * |-------------|--------|----------------|------------|
 * | null        | null   | Platform       | 0 (lowest) |
 * | null        | set    | User-global    | 1          |
 * | set         | null   | Namespace      | 2          |
 * | set         | set    | User×Namespace | 3 (highest)|
 *
 * ### Agent targeting
 *
 * [agentConfigId] is always required. Access control is enforced via the DEPLOYED_TO graph.
 *
 * ### Prompt reference
 *
 * [promptId] references a generic Prompt (agentConfigId = null) created and managed
 * automatically by the backend. Not exposed in the public API.
 *
 * ### Schedule
 *
 * [recurrence] describes the interval and time-of-day.
 * [planning] describes the date window and end condition.
 *
 * ### Name
 *
 * [name] is a free-form label (e.g. "Daily Digest", "Weekly Report").
 * It is normalized to a slug for the `tripleKey` uniqueness constraint via [ScheduledPromptNode.computeTripleKey],
 * so "Daily Digest" and "daily digest" would conflict in the same scope.
 */
data class ScheduledPrompt(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    val agentConfigId: UUID,
    /**
     * Reference to a generic Prompt (agentConfigId = null).
     * Managed by the backend; not exposed in the public API.
     */
    val promptTemplateId: UUID,
    val name: String,
    val description: String? = null,
    val recurrence: Recurrence,
    val planning: Planning,
    val enabled: Boolean = true,
    /** Next scheduled run time (UTC). Calculated on create/update, recalculated on re-enable. */
    val nextRunAt: Instant,
    /** Last time this scheduled prompt was triggered. Null until first run. */
    val lastRunAt: Instant? = null,
    /**
     * Opaque metadata map for external consumers.
     * AgentOS persists this field as-is without interpreting its content.
     */
    val externalMetadata: Map<String, Any?>? = null,
    /**
     * Only read when the agent is a LOOP agent (saving it on any other agent is refused).
     *
     * The scheduler materialises one run per user of the agent's deployment audience, so **the loop agent must be
     * deployed to a single user**: each user would otherwise trigger a full fan-out. That user must be an ADMIN of
     * the namespace to start cases for other users (rule of `FreeCaseStarterForUser`). The loop agent needs the
     * search tool's integration and a CASE integration granting `FreeCaseStarterForUser`.
     */
    val agentFlowConfig: AgentFlowConfig? = null,
) : Entity
