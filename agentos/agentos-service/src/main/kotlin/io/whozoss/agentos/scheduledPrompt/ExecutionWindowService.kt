package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Evaluates whether the current time falls within a configured execution window.
 *
 * ### Configuration format
 *
 * The window list is specified as a comma-separated string of `DayOfWeek HH:mm` pairs.
 * Pairs are ordered as alternating open/close boundaries: open₁, close₁, open₂, close₂, …
 *
 * Each boundary is a `java.time.DayOfWeek` name (case-insensitive) followed by a space
 * and an `HH:mm` time in UTC.
 *
 * Example — nightly Mon–Thu + continuous weekend:
 * ```
 * MONDAY 22:00,FRIDAY 05:00,FRIDAY 22:00,MONDAY 05:00
 * ```
 * This defines two windows:
 * - Window 1: Monday 22:00 UTC → Friday 05:00 UTC
 * - Window 2: Friday 22:00 UTC → Monday 05:00 UTC
 *
 * Windows are expressed as weekly offsets (minutes since Monday 00:00 UTC).
 * A window that crosses the Sunday→Monday boundary is handled via modular arithmetic.
 *
 * ### No windows configured
 *
 * When [SchedulerProperties.windows] is null or blank, [isWithinWindow] always returns `true`
 * — the scheduler runs continuously, preserving the existing behaviour.
 *
 * ### Validation
 *
 * [parseAndValidate] is called at construction time. If the string is malformed, all
 * errors are collected and logged; [isWithinWindow] then always returns `true` (fail-open)
 * so that a misconfiguration does not silently halt all scheduled executions.
 *
 * Validation rules:
 * - Even number of entries (open/close pairs).
 * - Each entry matches `DayOfWeek HH:mm` with a valid day name and time.
 * - Windows do not overlap (close must be strictly after open in weekly offset).
 * - Windows are ordered (each open must be strictly after the previous close).
 */
class ExecutionWindowService(windows: String?) {

    /**
     * A parsed window boundary: minutes elapsed since Monday 00:00 UTC within the week.
     * Range: [0, 7×24×60) = [0, 10080).
     */
    private data class WeeklyBoundary(val minuteOfWeek: Int)

    /**
     * A validated open/close window pair, both expressed as [WeeklyBoundary].
     * A window where [close] < [open] wraps around the Sunday→Monday boundary.
     */
    private data class Window(val open: WeeklyBoundary, val close: WeeklyBoundary)

    /**
     * Parsed and validated windows.
     * - `null`  → no windows configured (always-open) OR config was invalid (fail-open).
     * - non-null list → validated windows to evaluate.
     */
    private val parsedWindows: List<Window>?

    init {
        parsedWindows = parseAndValidate(windows)
    }

    /**
     * Returns `true` when [now] falls within any configured execution window.
     *
     * Returns `true` unconditionally when no windows are configured (always-open behaviour)
     * or when configuration parsing failed (fail-open).
     */
    fun isWithinWindow(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): Boolean {
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
     * Converts a [ZonedDateTime] (any zone, reinterpreted as UTC) to minutes elapsed
     * since Monday 00:00 UTC within the week.
     *
     * [DayOfWeek.getValue] returns 1 (Monday) … 7 (Sunday).
     * Subtracting 1 gives 0-based day index (Monday = 0, Sunday = 6).
     */
    private fun minuteOfWeek(now: ZonedDateTime): Int {
        val utc = now.withZoneSameInstant(ZoneOffset.UTC)
        val dayOffset = (utc.dayOfWeek.value - 1) * MINUTES_PER_DAY
        val timeOffset = utc.hour * 60 + utc.minute
        return dayOffset + timeOffset
    }

    /**
     * Parses [raw] into a list of [Window]s, collecting all validation errors.
     *
     * Returns `null` when [raw] is null or blank (no windows → always-open).
     * Returns `null` on parse failure (fail-open — [isWithinWindow] returns true).
     * Returns the validated windows on success.
     */
    private fun parseAndValidate(raw: String?): List<Window>? {
        if (raw.isNullOrBlank()) return null

        val entries = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val errors = mutableListOf<String>()

        if (entries.size % 2 != 0) {
            errors += "windows must contain an even number of entries (open/close pairs), got ${entries.size}"
        }

        val boundaries = entries.mapIndexedNotNull { index, entry ->
            parseBoundary(entry, index, errors)
        }

        if (errors.isNotEmpty()) {
            logger.error {
                "[ExecutionWindowService] Invalid scheduler windows configuration — " +
                    "scheduler will run continuously (fail-open). Errors: ${errors.joinToString("; ")}"
            }
            return null  // fail-open: null triggers always-open in isWithinWindow
        }

        // Pair into windows: (0,1), (2,3), …
        val windows = boundaries.chunked(2) { (open, close) -> Window(open, close) }

        // Validate: within each window, close must differ from open
        windows.forEachIndexed { i, w ->
            if (w.open == w.close) {
                errors += "window[$i]: open and close are identical (${entries[i * 2]})"
            }
        }

        // Validate ordering: each open must be strictly after the previous close,
        // and within a window the close must be strictly after the open (no wrap for ordering check).
        // Wrap-around windows (close < open) are valid but must not overlap each other.
        for (i in windows.indices) {
            val w = windows[i]
            // Check no zero-length window
            if (w.open.minuteOfWeek == w.close.minuteOfWeek) continue // already reported above

            if (i > 0) {
                val prev = windows[i - 1]
                val prevIsWrapAround = prev.close.minuteOfWeek < prev.open.minuteOfWeek
                val currIsWrapAround = w.close.minuteOfWeek < w.open.minuteOfWeek
                if (prevIsWrapAround && currIsWrapAround) {
                    errors += "window[$i]: only one wrap-around window (crossing Sunday\u2192Monday) is allowed; " +
                        "window[${i - 1}] and window[$i] both wrap around"
                } else if (w.open.minuteOfWeek <= prev.close.minuteOfWeek) {
                    errors += "window[$i] open (${entries[i * 2]}) must be strictly after " +
                        "window[${i - 1}] close (${entries[(i - 1) * 2 + 1]})"
                }
            }
        }

        if (errors.isNotEmpty()) {
            logger.error {
                "[ExecutionWindowService] Invalid scheduler windows configuration — " +
                    "scheduler will run continuously (fail-open). Errors: ${errors.joinToString("; ")}"
            }
            return null  // fail-open: null triggers always-open in isWithinWindow
        }

        logger.info {
            "[ExecutionWindowService] Execution windows configured: " +
                windows.joinToString(", ") { w ->
                    "[${minuteOfWeekToLabel(w.open.minuteOfWeek)} → ${minuteOfWeekToLabel(w.close.minuteOfWeek)}]"
                }
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
        val minuteOfWeek = (day.value - 1) * MINUTES_PER_DAY + time.hour * 60 + time.minute
        return WeeklyBoundary(minuteOfWeek)
    }

    /** Human-readable label for a minuteOfWeek value, used in log messages. */
    private fun minuteOfWeekToLabel(minuteOfWeek: Int): String {
        val day = DayOfWeek.of(minuteOfWeek / MINUTES_PER_DAY + 1)
        val hour = (minuteOfWeek % MINUTES_PER_DAY) / 60
        val minute = minuteOfWeek % 60
        return "$day %02d:%02d UTC".format(hour, minute)
    }

    companion object : KLogging() {
        private val MINUTES_PER_DAY = Duration.ofDays(1).toMinutes().toInt()
    }
}
