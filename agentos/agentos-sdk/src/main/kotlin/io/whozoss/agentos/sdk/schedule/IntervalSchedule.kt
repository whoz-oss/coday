package io.whozoss.agentos.sdk.schedule

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

/**
 * Interval-based recurrence definition for a [io.whozoss.agentos.schedule.Schedule].
 *
 * Describes *when* a schedule fires using a start anchor, a fixed interval, an
 * optional day-of-week filter, and an optional terminal condition.
 *
 * Interval format — a positive integer followed by a unit suffix:
 * - `"30min"` — every 30 minutes (1–59)
 * - `"6h"`   — every 6 hours   (1–24)
 * - `"14d"`  — every 14 days   (1–31)
 * - `"3M"`   — every 3 months  (1–12)
 *
 * [daysOfWeek] is a list of ISO weekday integers (0 = Sunday … 6 = Saturday).
 * When present the scheduler skips occurrences that do not land on one of the
 * listed days, advancing by one full interval until a matching day is found —
 * the same algorithm used by the Coday TypeScript scheduler.
 *
 * [endCondition] is optional.  When absent the schedule repeats indefinitely.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) allows forward-compatible
 * deserialisation when new fields are added in future SDK versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class IntervalSchedule(
    val startTimestamp: Instant,
    val interval: String,
    val daysOfWeek: List<Int>? = null,
    val endCondition: EndCondition? = null,
)
