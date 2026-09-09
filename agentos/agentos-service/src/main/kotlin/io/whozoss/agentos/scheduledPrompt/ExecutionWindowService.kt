package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Evaluates whether the current time falls within a configured execution window.
 *
 * ### Configuration format
 *
 * The constructor receives a flat list of **boundaries**, each of the form `DayOfWeek HH:mm`.
 * Spring produces this list by splitting the comma-separated value of
 * [SchedulerProperties.windows] — this class never splits on `,` itself.
 *
 * Boundaries are ordered as alternating open/close pairs: open₁, close₁, open₂, close₂, …
 * and are grouped into [Window]s by position in [parseAndValidate] (`chunked(2)`).
 * The open/close role is therefore carried by the **index**, not by the type — a deliberate
 * trade-off to keep the whole configuration in a single environment variable.
 *
 * Each boundary is a `java.time.DayOfWeek` name (case-insensitive) followed by a space
 * and an `HH:mm` time in UTC.
 *
 * Example — continuous Mon-night→Fri-morning + continuous weekend:
 * ```
 * AGENTOS_PROMPT_SCHEDULER_WINDOWS=MONDAY 22:00,FRIDAY 05:00,FRIDAY 22:00,MONDAY 05:00
 * ```
 * Spring binds this to `["MONDAY 22:00", "FRIDAY 05:00", "FRIDAY 22:00", "MONDAY 05:00"]`,
 * which this class groups into two windows:
 * - Window 1: Monday 22:00 UTC → Friday 05:00 UTC
 * - Window 2: Friday 22:00 UTC → Monday 05:00 UTC
 *
 * Windows are evaluated as weekly offsets (minutes since Monday 00:00 UTC).
 * A window that crosses the Sunday→Monday boundary is handled via modular arithmetic.
 *
 * ### No windows configured
 *
 * When the list is empty, [isWithinWindow] always returns `true` — the scheduler runs
 * continuously, preserving the existing behaviour.
 *
 * ### Validation
 *
 * [parseAndValidate] is called at construction time. If the configuration is malformed, all
 * errors are collected and logged; [isWithinWindow] then always returns `true` (fail-open)
 * so that a misconfiguration does not silently halt all scheduled executions.
 *
 * Validation rules:
 * - Even number of boundaries (each window needs both an open and a close).
 * - Each boundary matches `DayOfWeek HH:mm` with a valid day name and time.
 * - Within a window, open and close differ (no zero-length window).
 * - Windows are ordered and non-overlapping (each open strictly after the previous close).
 * - At most one wrap-around window (crossing Sunday→Monday), since two such windows
 *   cannot be ordered by weekly offset and may overlap undetected.
 */
@Service
class ExecutionWindowService(properties: SchedulerProperties, private val clock: Clock = Clock.systemUTC()) {

    /**
     * A parsed window boundary, retaining the original [day] and [time] for readability.
     * [minuteOfWeek] is derived: minutes elapsed since Monday 00:00 UTC within the week.
     * Range: [0, 7×24×60) = [0, 10080).
     */
    private data class WeeklyBoundary(val day: DayOfWeek, val time: LocalTime) {
        val minuteOfWeek: Int get() = (day.value - 1) * 24 * 60 + time.toSecondOfDay() / 60
        override fun toString(): String = "$day ${time} UTC"
    }

    /**
     * A validated open/close window pair, both expressed as [WeeklyBoundary].
     * A window where [close] < [open] wraps around the Sunday→Monday boundary.
     */
    private data class Window(val open: WeeklyBoundary, val close: WeeklyBoundary) {
        /** True when the window crosses the Sunday→Monday boundary (close offset before open offset). */
        val isWrapAround: Boolean get() = close.minuteOfWeek < open.minuteOfWeek
        override fun toString(): String = "[$open → $close]"
    }

    /**
     * Parsed and validated windows.
     * - `null`  → no windows configured (always-open) OR config was invalid (fail-open).
     * - non-null list → validated windows to evaluate.
     */
    private val parsedWindows: List<Window>?

    init {
        parsedWindows = parseAndValidate(properties.windows)
    }

    /**
     * Returns `true` when the current time (from the injected [clock]) falls within any
     * configured execution window (execution allowed).
     *
     * Returns `true` unconditionally when no windows are configured (always-open behaviour)
     * or when configuration parsing failed (fail-open).
     */
    fun isWithinExecutionWindow(): Boolean = isWithinExecutionWindow(Instant.now(clock))

    /**
     * Returns `true` when [now] falls within any configured execution window.
     * Exposed for callers that already hold an [Instant] (e.g. [SchedulerScanner]).
     */
    fun isWithinExecutionWindow(now: Instant): Boolean {
        val windows = parsedWindows ?: return true
        val current = minuteOfWeek(now)
        return windows.any { window -> isInWindow(current, window) }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when [minuteOfWeek] falls within [window].
     *
     * Handles wrap-around (e.g. Friday 22:00 → Monday 05:00): when close < open,
     * the window spans the Sunday→Monday boundary and the check is inverted.
     */
    private fun isInWindow(minuteOfWeek: Int, window: Window): Boolean {
        val open = window.open.minuteOfWeek
        val close = window.close.minuteOfWeek
        return if (open <= close) {
            // Normal window: open ≤ current < close
            minuteOfWeek >= open && minuteOfWeek < close
        } else {
            // Wrap-around: current ≥ open OR current < close
            minuteOfWeek >= open || minuteOfWeek < close
        }
    }

    /**
     * Converts an [Instant] to minutes elapsed since Monday 00:00 UTC within the week.
     */
    private fun minuteOfWeek(now: Instant): Int {
        val utc = now.atZone(ZoneOffset.UTC)
        return WeeklyBoundary(utc.dayOfWeek, utc.toLocalTime()).minuteOfWeek
    }

    /**
     * Parses [raw] into a list of [Window]s, collecting all validation errors.
     *
     * Returns `null` when [raw] is empty (no windows → always-open).
     * Returns `null` on parse failure (fail-open — [isWithinWindow] returns true).
     * Returns the validated windows on success.
     */
    private fun parseAndValidate(raw: List<String>): List<Window>? {
        if (raw.isEmpty()) return null

        val entries = raw.map { it.trim() }.filter { it.isNotEmpty() }
        val errors = mutableListOf<String>()

        if (entries.size % 2 != 0) {
            errors += "windows must contain an even number of entries (open/close pairs), got ${entries.size}"
        }

        val boundaries = entries.mapIndexedNotNull { index, entry ->
            parseBoundary(entry, index, errors)
        }

        if (errors.isNotEmpty()) {
            logErrors(errors)
            return null
        }

        // Pair into windows: (0,1), (2,3), …
        val windows = boundaries.chunked(2) { (open, close) -> Window(open, close) }

        // Validate: within each window, open and close must differ
        windows.forEachIndexed { i, w ->
            if (w.open == w.close) {
                errors += "window[$i]: open and close are identical (${w.open})"
            }
        }

        // Validate: at most one wrap-around window. Two of them cannot be ordered by weekly
        // offset (both have a high open and a low close), so the ordering check below cannot
        // detect an overlap between them. Checked globally rather than pairwise so the rule
        // matches its stated intent regardless of window positions.
        val wrapAroundWindows = windows.filter { it.isWrapAround }
        if (wrapAroundWindows.size > 1) {
            errors += "at most one wrap-around window (crossing Sunday→Monday) is allowed, " +
                "got ${wrapAroundWindows.size}: ${wrapAroundWindows.joinToString(", ")}"
        }

        // Validate ordering: each open must be strictly after the previous close.
        // Skipped when a wrap-around pair was already reported — raw offset comparison is
        // meaningless in that case and would emit a confusing secondary error.
        if (wrapAroundWindows.size <= 1) {
            for (i in 1 until windows.size) {
                val w = windows[i]
                val prev = windows[i - 1]
                if (w.open == w.close || prev.open == prev.close) continue // already reported above

                if (w.open.minuteOfWeek <= prev.close.minuteOfWeek) {
                    errors += "window[$i] open (${w.open}) must be strictly after window[${i - 1}] close (${prev.close})"
                }
            }
        }

        if (errors.isNotEmpty()) {
            logErrors(errors)
            return null
        }

        logger.info {
            "[ExecutionWindowService] Execution windows configured: ${windows.joinToString(", ")}"
        }
        return windows
    }

    /**
     * Parses a single boundary entry of the form `DAYOFWEEK HH:mm`.
     * Appends to [errors] on failure and returns `null`.
     */
    private fun parseBoundary(entry: String, index: Int, errors: MutableList<String>): WeeklyBoundary? {
        val parts = entry.split(" ")
        if (parts.size != 2) {
            errors += "entry[$index] '$entry': expected 'DAYOFWEEK HH:mm'"
            return null
        }
        val day = runCatching { DayOfWeek.valueOf(parts[0].uppercase()) }.getOrElse {
            errors += "entry[$index] '$entry': unknown day '${parts[0]}' (expected MONDAY…SUNDAY)"
            return null
        }
        val time = runCatching { LocalTime.parse(parts[1]) }.getOrElse {
            errors += "entry[$index] '$entry': invalid time '${parts[1]}' (expected HH:mm)"
            return null
        }
        return WeeklyBoundary(day, time)
    }

    private fun logErrors(errors: List<String>) {
        logger.error {
            "[ExecutionWindowService] Invalid scheduler windows configuration — " +
                "scheduler will run continuously (fail-open). Errors: ${errors.joinToString("; ")}"
        }
    }

    companion object : KLogging()
}
