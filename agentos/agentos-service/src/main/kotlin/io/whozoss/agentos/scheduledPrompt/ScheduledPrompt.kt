package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
 * [every] + [unit] define the interval (e.g. every 2 weeks).
 * [days] is an optional filter on days of the week; empty list = no filter.
 * [timeUtc] is the execution time in UTC.
 */
data class Recurrence(
    val every: Int,
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
 * - OCCURRENCES: stops after [occurrenceCount] executions (required, > 0).
 */
data class Planning(
    val startDate: LocalDate,
    val endType: SchedulerEndType = SchedulerEndType.NEVER,
    val endDate: LocalDate? = null,
    val occurrenceCount: Int? = null,
)

// ---------------------------------------------------------------------------
// Aggregate root
// ---------------------------------------------------------------------------

/**
 * A declarative, quasi-immutable definition of a scheduled prompt.
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
) : Entity
