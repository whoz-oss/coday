package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Unit tests for [ExecutionWindowService].
 *
 * Uses fixed [ZonedDateTime] values — no Spring context, no Clock injection.
 *
 * Business-hours config used in most tests:
 *   `MONDAY 22:00,FRIDAY 05:00,FRIDAY 22:00,MONDAY 05:00`
 *   Window 1: Mon 22:00 UTC → Fri 05:00 UTC  (continuous nightly Mon–Thu + early Fri)
 *   Window 2: Fri 22:00 UTC → Mon 05:00 UTC  (continuous weekend)
 */
class ExecutionWindowServiceSpec : StringSpec() {

    /** Canonical business-hours config: nightly Mon–Thu + continuous Fri–Mon. */
    private val businessHoursConfig = "MONDAY 22:00,FRIDAY 05:00,FRIDAY 22:00,MONDAY 05:00"

    private fun at(day: String, hour: Int, minute: Int = 0): ZonedDateTime {
        val dayOfWeek = java.time.DayOfWeek.valueOf(day)
        // Use any Monday as anchor (2024-01-01 is a Monday)
        val monday = java.time.LocalDate.of(2024, 1, 1)
        val date = monday.plusDays((dayOfWeek.value - 1).toLong())
        return ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), ZoneOffset.UTC)
    }

    init {

        // -------------------------------------------------------------------------
        // No windows configured — always open
        // -------------------------------------------------------------------------

        "no windows (null): always within window" {
            val svc = ExecutionWindowService(null)
            svc.isWithinWindow(at("MONDAY", 10)).shouldBeTrue()
            svc.isWithinWindow(at("SATURDAY", 14)).shouldBeTrue()
        }

        "no windows (blank): always within window" {
            val svc = ExecutionWindowService("   ")
            svc.isWithinWindow(at("WEDNESDAY", 9)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Business-hours config — inside windows
        // -------------------------------------------------------------------------

        "inside window 1: Monday night is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("MONDAY", 23)).shouldBeTrue()
        }

        "inside window 1: Tuesday 02:00 is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("TUESDAY", 2)).shouldBeTrue()
        }

        "inside window 1: Thursday 23:59 is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("THURSDAY", 23, 59)).shouldBeTrue()
        }

        "inside window 1: Friday 04:59 is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("FRIDAY", 4, 59)).shouldBeTrue()
        }

        "inside window 2: Friday night is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("FRIDAY", 23)).shouldBeTrue()
        }

        "inside window 2: Saturday is always within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("SATURDAY", 0)).shouldBeTrue()
            svc.isWithinWindow(at("SATURDAY", 12)).shouldBeTrue()
            svc.isWithinWindow(at("SATURDAY", 23, 59)).shouldBeTrue()
        }

        "inside window 2: Sunday is always within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("SUNDAY", 0)).shouldBeTrue()
            svc.isWithinWindow(at("SUNDAY", 12)).shouldBeTrue()
            svc.isWithinWindow(at("SUNDAY", 23, 59)).shouldBeTrue()
        }

        "inside window 2: Monday 04:59 is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("MONDAY", 4, 59)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Business-hours config — outside windows (business hours)
        // -------------------------------------------------------------------------

        "outside window: Monday 10:00 is outside window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("MONDAY", 10)).shouldBeFalse()
        }

        // Note: window 1 is MONDAY 22:00 → FRIDAY 05:00, a *continuous* block.
        // Tuesday 09:00, Wednesday 14:00, Thursday 08:00 are all inside that continuous
        // window (Mon night through Fri morning). To restrict to nightly slots only,
        // the operator would need to configure one window per night.
        "inside window 1: Tuesday 09:00 is within the continuous Mon–Fri window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("TUESDAY", 9)).shouldBeTrue()
        }

        "inside window 1: Wednesday 14:00 is within the continuous Mon–Fri window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "inside window 1: Thursday 08:00 is within the continuous Mon–Fri window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("THURSDAY", 8)).shouldBeTrue()
        }

        "outside window: Friday 10:00 is outside window (between window 1 close and window 2 open)" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("FRIDAY", 10)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // Exact boundary conditions (inclusive open, exclusive close)
        // -------------------------------------------------------------------------

        "boundary: exactly at window 1 open (Monday 22:00) is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("MONDAY", 22, 0)).shouldBeTrue()
        }

        "boundary: exactly at window 1 close (Friday 05:00) is outside window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("FRIDAY", 5, 0)).shouldBeFalse()
        }

        "boundary: exactly at window 2 open (Friday 22:00) is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("FRIDAY", 22, 0)).shouldBeTrue()
        }

        "boundary: exactly at window 2 close (Monday 05:00) is outside window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("MONDAY", 5, 0)).shouldBeFalse()
        }

        "boundary: one minute before window 1 open (Monday 21:59) is outside window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("MONDAY", 21, 59)).shouldBeFalse()
        }

        "boundary: one minute before window 1 close (Friday 04:59) is within window" {
            val svc = ExecutionWindowService(businessHoursConfig)
            svc.isWithinWindow(at("FRIDAY", 4, 59)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Single nightly window (no weekend extension)
        // -------------------------------------------------------------------------

        "single nightly window: within window" {
            // Every night 22:00 → 05:00 (wraps midnight)
            val svc = ExecutionWindowService("MONDAY 22:00,TUESDAY 05:00")
            svc.isWithinWindow(at("MONDAY", 23)).shouldBeTrue()
            svc.isWithinWindow(at("TUESDAY", 2)).shouldBeTrue()
        }

        "single nightly window: outside window" {
            val svc = ExecutionWindowService("MONDAY 22:00,TUESDAY 05:00")
            svc.isWithinWindow(at("MONDAY", 10)).shouldBeFalse()
            svc.isWithinWindow(at("TUESDAY", 6)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // Wrap-around: window spanning Sunday → Monday midnight
        // -------------------------------------------------------------------------

        "wrap-around: Sunday 23:00 is within a Sun 22:00 → Mon 05:00 window" {
            val svc = ExecutionWindowService("SUNDAY 22:00,MONDAY 05:00")
            svc.isWithinWindow(at("SUNDAY", 23)).shouldBeTrue()
        }

        "wrap-around: Monday 02:00 is within a Sun 22:00 → Mon 05:00 window" {
            val svc = ExecutionWindowService("SUNDAY 22:00,MONDAY 05:00")
            svc.isWithinWindow(at("MONDAY", 2)).shouldBeTrue()
        }

        "wrap-around: Monday 06:00 is outside a Sun 22:00 → Mon 05:00 window" {
            val svc = ExecutionWindowService("SUNDAY 22:00,MONDAY 05:00")
            svc.isWithinWindow(at("MONDAY", 6)).shouldBeFalse()
        }

        "wrap-around: Sunday 21:59 is outside a Sun 22:00 → Mon 05:00 window" {
            val svc = ExecutionWindowService("SUNDAY 22:00,MONDAY 05:00")
            svc.isWithinWindow(at("SUNDAY", 21, 59)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // Invalid configuration — fail-open (always within window)
        // -------------------------------------------------------------------------

        "invalid config: odd number of entries — fail-open" {
            val svc = ExecutionWindowService("MONDAY 22:00,FRIDAY 05:00,FRIDAY 22:00")
            svc.isWithinWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: unknown day name — fail-open" {
            val svc = ExecutionWindowService("FUNDAY 22:00,FRIDAY 05:00")
            svc.isWithinWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: malformed time — fail-open" {
            val svc = ExecutionWindowService("MONDAY 25:00,FRIDAY 05:00")
            svc.isWithinWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: missing time part — fail-open" {
            val svc = ExecutionWindowService("MONDAY,FRIDAY 05:00")
            svc.isWithinWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: overlapping windows — fail-open" {
            // Window 2 open (TUESDAY 08:00) is before window 1 close (WEDNESDAY 05:00)
            val svc = ExecutionWindowService("MONDAY 22:00,WEDNESDAY 05:00,TUESDAY 08:00,THURSDAY 05:00")
            svc.isWithinWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: identical open and close — fail-open" {
            val svc = ExecutionWindowService("MONDAY 22:00,MONDAY 22:00")
            svc.isWithinWindow(at("MONDAY", 22)).shouldBeTrue()
        }

        "invalid config: two overlapping wrap-around windows — fail-open" {
            // Both windows cross the Sunday→Monday boundary and overlap:
            //   Window 1: SUNDAY 20:00 → MONDAY 02:00
            //   Window 2: SUNDAY 22:00 → MONDAY 05:00  (starts inside window 1)
            val svc = ExecutionWindowService("SUNDAY 20:00,MONDAY 02:00,SUNDAY 22:00,MONDAY 05:00")
            svc.isWithinWindow(at("SUNDAY", 23)).shouldBeTrue()  // fail-open
        }

        // -------------------------------------------------------------------------
        // Case-insensitive day names
        // -------------------------------------------------------------------------

        "case-insensitive: lowercase day names are accepted" {
            val svc = ExecutionWindowService("monday 22:00,friday 05:00,friday 22:00,monday 05:00")
            svc.isWithinWindow(at("MONDAY", 23)).shouldBeTrue()
            svc.isWithinWindow(at("MONDAY", 10)).shouldBeFalse()
        }

        "case-insensitive: mixed-case day names are accepted" {
            val svc = ExecutionWindowService("Monday 22:00,Friday 05:00,Friday 22:00,Monday 05:00")
            svc.isWithinWindow(at("SATURDAY", 12)).shouldBeTrue()
        }
    }
}
