package io.whozoss.agentos.schedule

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import java.time.Instant
import java.util.UUID

/**
 * A temporal trigger that injects a [io.whozoss.agentos.sdk.caseEvent.MessageEvent] into a
 * target case (or creates a new case) when it fires.
 *
 * Two trigger strategies are mutually exclusive:
 * - [triggerAt] — one-shot: fires once at the given instant
 * - [intervalSchedule] — recurring: fires on a repeating interval
 *
 * The scheduler only reads [nextTriggerAt]; the logic for computing the next
 * trigger from the chosen strategy is encapsulated here and in the scheduler
 * service — the persistence layer is strategy-agnostic.
 *
 * [oneShot] controls lifecycle: when `true` the schedule is soft-deleted after
 * its first successful trigger regardless of strategy.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because [Entity]
 * exposes a computed `id` property that Jackson serialises but which is not a
 * constructor parameter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Schedule(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val caseId: UUID? = null,
    val agentName: String? = null,
    val userId: UUID? = null,
    val message: String,
    val enabled: Boolean = true,
    val oneShot: Boolean = false,
    // --- Temporal definition (one active strategy) ---
    val triggerAt: Instant? = null,
    val intervalSchedule: IntervalSchedule? = null,
    // --- Tracking ---
    val nextTriggerAt: Instant? = null,
    val lastTriggeredAt: Instant? = null,
    val occurrenceCount: Int = 0,
) : Entity
