package io.whozoss.agentos.caseDefinition

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Recurrence frequency used internally and in [CronExpressionConverter]. Only [DAILY] and [WEEKLY] are supported. */
enum class ScheduleFrequency {
    DAILY,
    WEEKLY,
}

/**
 * Converts between the API's `(frequency, dayOfWeek, timeUtc)` representation and
 * a standard 5-field cron expression (`minute hour dom month dow`).
 *
 * ### Supported formats
 *
 * | frequency | dayOfWeek | timeUtc | cronExpression    |
 * |-----------|-----------|---------|-------------------|
 * | DAILY     | null      | 09:00   | `0 9 * * *`       |
 * | WEEKLY    | MON       | 09:00   | `0 9 * * MON`     |
 * | WEEKLY    | FRI       | 14:30   | `30 14 * * FRI`   |
 *
 * ### Day-of-week values
 *
 * Standard cron abbreviations: `MON TUE WED THU FRI SAT SUN`.
 * Values are accepted case-insensitively and normalised to uppercase on write.
 *
 * ### Validation
 *
 * - [WEEKLY][ScheduleFrequency.WEEKLY] without a valid `dayOfWeek` throws 400.
 * - `timeUtc` must be in `HH:mm` format; invalid values throw 400.
 * - [fromCron] on an unrecognised pattern throws [IllegalArgumentException] —
 *   callers should treat this as a data-integrity error.
 */
object CronExpressionConverter {

    /** Accepted day-of-week abbreviations (uppercase). */
    val VALID_DAYS: Set<String> = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    fun toCron(frequency: ScheduleFrequency, dayOfWeek: String?, timeUtc: String): String {
        val (hour, minute) = parseTime(timeUtc)
        return when (frequency) {
            ScheduleFrequency.DAILY -> "$minute $hour * * *"
            ScheduleFrequency.WEEKLY -> {
                val day = validateDay(dayOfWeek)
                "$minute $hour * * $day"
            }
        }
    }

    fun fromCron(cron: String): CronSchedule {
        val parts = cron.trim().split("\\s+".toRegex())
        require(parts.size == 5) { "Expected 5-field cron, got: '$cron'" }
        val (minute, hour, dom, month, dow) = parts
        require(dom == "*" && month == "*") { "Unsupported cron pattern (dom/month must be *): '$cron'" }
        val timeUtc = "%02d:%02d".format(hour.toInt(), minute.toInt())
        return when {
            dow == "*" -> CronSchedule(frequency = ScheduleFrequency.DAILY, dayOfWeek = null, timeUtc = timeUtc)
            dow.uppercase() in VALID_DAYS -> CronSchedule(
                frequency = ScheduleFrequency.WEEKLY,
                dayOfWeek = dow.uppercase(),
                timeUtc = timeUtc,
            )
            else -> throw IllegalArgumentException("Unrecognised day-of-week in cron: '$dow'")
        }
    }

    private fun parseTime(timeUtc: String): Pair<Int, Int> {
        val parts = timeUtc.split(":")
        if (parts.size != 2) badTime(timeUtc)
        val hour = parts[0].toIntOrNull() ?: badTime(timeUtc)
        val minute = parts[1].toIntOrNull() ?: badTime(timeUtc)
        if (hour !in 0..23 || minute !in 0..59) badTime(timeUtc)
        return Pair(hour, minute)
    }

    private fun validateDay(dayOfWeek: String?): String {
        if (dayOfWeek == null) throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "dayOfWeek is required for WEEKLY frequency",
        )
        val upper = dayOfWeek.uppercase()
        if (upper !in VALID_DAYS) throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid dayOfWeek '$dayOfWeek'. Must be one of: ${VALID_DAYS.joinToString()}",
        )
        return upper
    }

    private fun badTime(timeUtc: String): Nothing = throw ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid timeUtc '$timeUtc'. Must be in HH:mm format (e.g. \"09:00\").",
    )
}

/**
 * Result of [CronExpressionConverter.fromCron]: the API-facing schedule fields
 * reconstructed from a stored cron expression.
 */
data class CronSchedule(
    val frequency: ScheduleFrequency,
    /** Null for [ScheduleFrequency.DAILY]; a [CronExpressionConverter.VALID_DAYS] value for WEEKLY. */
    val dayOfWeek: String?,
    /** UTC time in `HH:mm` format, e.g. `"09:00"`. */
    val timeUtc: String,
)
