package io.whozoss.agentos.schedule

/**
 * Policy controlling how the scheduler handles missed occurrences when the
 * service was down or the schedule was temporarily disabled.
 *
 * Applied globally via `agentos.scheduler.catch-up-policy` in `application.yml`.
 * Per-schedule overrides are not yet supported.
 *
 * - [ALL]   — trigger every missed occurrence in chronological order.
 * - [FIRST] — trigger only the oldest missed occurrence.
 * - [LAST]  — trigger only the most recent missed occurrence (default).
 * - [NONE]  — skip all missed occurrences; resume from the next future trigger.
 */
enum class CatchUpPolicy {
    ALL,
    FIRST,
    LAST,
    NONE,
}
