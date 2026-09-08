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
 *   Execution window 1: Mon 22:00 UTC → Fri 05:00 UTC
 *   Execution window 2: Fri 22:00 UTC → Mon 05:00 UTC
 */
class ExecutionWindowServiceSpec : StringSpec() {

    private val businessHoursConfig = listOf("MONDAY 22:00", "FRIDAY 05:00", "FRIDAY 22:00", "MONDAY 05:00")

    private fun svc(windows: List<String>) = ExecutionWindowService(SchedulerProperties(windows = windows))

    private fun at(day: String, hour: Int, minute: Int = 0): ZonedDateTime {
        val dayOfWeek = java.time.DayOfWeek.valueOf(day)
        val monday = java.time.LocalDate.of(2024, 1, 1)
        val date = monday.plusDays((dayOfWeek.value - 1).toLong())
        return ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), ZoneOffset.UTC)
    }

    init {

        // -------------------------------------------------------------------------
        // No windows configured — always open
        // -------------------------------------------------------------------------

        "no windows (empty list): always within execution window" {
            val svc = svc(emptyList())
            svc.isWithinExecutionWindow(at("MONDAY", 10)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("SATURDAY", 14)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Inside execution windows
        // -------------------------------------------------------------------------

        "inside window 1: Monday night is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("MONDAY", 23)).shouldBeTrue()
        }

        "inside window 1: Tuesday 02:00 is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("TUESDAY", 2)).shouldBeTrue()
        }

        "inside window 1: Thursday 23:59 is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("THURSDAY", 23, 59)).shouldBeTrue()
        }

        "inside window 1: Friday 04:59 is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("FRIDAY", 4, 59)).shouldBeTrue()
        }

        "inside window 2: Friday night is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("FRIDAY", 23)).shouldBeTrue()
        }

        "inside window 2: Saturday is always within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("SATURDAY", 0)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("SATURDAY", 12)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("SATURDAY", 23, 59)).shouldBeTrue()
        }

        "inside window 2: Sunday is always within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("SUNDAY", 0)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("SUNDAY", 12)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("SUNDAY", 23, 59)).shouldBeTrue()
        }

        "inside window 2: Monday 04:59 is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("MONDAY", 4, 59)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Outside execution windows (business hours = blocked)
        // -------------------------------------------------------------------------

        "outside window: Monday 10:00 is blocked" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("MONDAY", 10)).shouldBeFalse()
        }

        "inside window 1: Tuesday 09:00 is within the continuous Mon–Fri window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("TUESDAY", 9)).shouldBeTrue()
        }

        "inside window 1: Wednesday 14:00 is within the continuous Mon–Fri window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "inside window 1: Thursday 08:00 is within the continuous Mon–Fri window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("THURSDAY", 8)).shouldBeTrue()
        }

        "outside window: Friday 10:00 is blocked (between window 1 close and window 2 open)" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("FRIDAY", 10)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // Exact boundary conditions (inclusive open, exclusive close)
        // -------------------------------------------------------------------------

        "boundary: exactly at window 1 open (Monday 22:00) is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("MONDAY", 22, 0)).shouldBeTrue()
        }

        "boundary: exactly at window 1 close (Friday 05:00) is blocked" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("FRIDAY", 5, 0)).shouldBeFalse()
        }

        "boundary: exactly at window 2 open (Friday 22:00) is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("FRIDAY", 22, 0)).shouldBeTrue()
        }

        "boundary: exactly at window 2 close (Monday 05:00) is blocked" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("MONDAY", 5, 0)).shouldBeFalse()
        }

        "boundary: one minute before window 1 open (Monday 21:59) is blocked" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("MONDAY", 21, 59)).shouldBeFalse()
        }

        "boundary: one minute before window 1 close (Friday 04:59) is within execution window" {
            val svc = svc(businessHoursConfig)
            svc.isWithinExecutionWindow(at("FRIDAY", 4, 59)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Single nightly window
        // -------------------------------------------------------------------------

        "single nightly window: within execution window" {
            val svc = svc(listOf("MONDAY 22:00", "TUESDAY 05:00"))
            svc.isWithinExecutionWindow(at("MONDAY", 23)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("TUESDAY", 2)).shouldBeTrue()
        }

        "single nightly window: outside execution window (blocked)" {
            val svc = svc(listOf("MONDAY 22:00", "TUESDAY 05:00"))
            svc.isWithinExecutionWindow(at("MONDAY", 10)).shouldBeFalse()
            svc.isWithinExecutionWindow(at("TUESDAY", 6)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // Wrap-around: window spanning Sunday → Monday midnight
        // -------------------------------------------------------------------------

        "wrap-around: Sunday 23:00 is within a Sun 22:00 → Mon 05:00 execution window" {
            val svc = svc(listOf("SUNDAY 22:00", "MONDAY 05:00"))
            svc.isWithinExecutionWindow(at("SUNDAY", 23)).shouldBeTrue()
        }

        "wrap-around: Monday 02:00 is within a Sun 22:00 → Mon 05:00 execution window" {
            val svc = svc(listOf("SUNDAY 22:00", "MONDAY 05:00"))
            svc.isWithinExecutionWindow(at("MONDAY", 2)).shouldBeTrue()
        }

        "wrap-around: Monday 06:00 is outside a Sun 22:00 → Mon 05:00 execution window (blocked)" {
            val svc = svc(listOf("SUNDAY 22:00", "MONDAY 05:00"))
            svc.isWithinExecutionWindow(at("MONDAY", 6)).shouldBeFalse()
        }

        "wrap-around: Sunday 21:59 is outside a Sun 22:00 → Mon 05:00 execution window (blocked)" {
            val svc = svc(listOf("SUNDAY 22:00", "MONDAY 05:00"))
            svc.isWithinExecutionWindow(at("SUNDAY", 21, 59)).shouldBeFalse()
        }

        // -------------------------------------------------------------------------
        // Invalid configuration — fail-open
        // -------------------------------------------------------------------------

        "invalid config: odd number of entries — fail-open" {
            val svc = svc(listOf("MONDAY 22:00", "FRIDAY 05:00", "FRIDAY 22:00"))
            svc.isWithinExecutionWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: unknown day name — fail-open" {
            val svc = svc(listOf("FUNDAY 22:00", "FRIDAY 05:00"))
            svc.isWithinExecutionWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: malformed time — fail-open" {
            val svc = svc(listOf("MONDAY 25:00", "FRIDAY 05:00"))
            svc.isWithinExecutionWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: missing time part — fail-open" {
            val svc = svc(listOf("MONDAY", "FRIDAY 05:00"))
            svc.isWithinExecutionWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: overlapping windows — fail-open" {
            val svc = svc(listOf("MONDAY 22:00", "WEDNESDAY 05:00", "TUESDAY 08:00", "THURSDAY 05:00"))
            svc.isWithinExecutionWindow(at("WEDNESDAY", 14)).shouldBeTrue()
        }

        "invalid config: identical open and close — fail-open" {
            val svc = svc(listOf("MONDAY 22:00", "MONDAY 22:00"))
            svc.isWithinExecutionWindow(at("MONDAY", 22)).shouldBeTrue()
        }

        "invalid config: two overlapping wrap-around windows — fail-open" {
            val svc = svc(listOf("SUNDAY 20:00", "MONDAY 02:00", "SUNDAY 22:00", "MONDAY 05:00"))
            svc.isWithinExecutionWindow(at("SUNDAY", 23)).shouldBeTrue()
        }

        // -------------------------------------------------------------------------
        // Case-insensitive day names
        // -------------------------------------------------------------------------

        "case-insensitive: lowercase day names are accepted" {
            val svc = svc(listOf("monday 22:00", "friday 05:00", "friday 22:00", "monday 05:00"))
            svc.isWithinExecutionWindow(at("MONDAY", 23)).shouldBeTrue()
            svc.isWithinExecutionWindow(at("MONDAY", 10)).shouldBeFalse()
        }

        "case-insensitive: mixed-case day names are accepted" {
            val svc = svc(listOf("Monday 22:00", "Friday 05:00", "Friday 22:00", "Monday 05:00"))
            svc.isWithinExecutionWindow(at("SATURDAY", 12)).shouldBeTrue()
        }
    }
}
