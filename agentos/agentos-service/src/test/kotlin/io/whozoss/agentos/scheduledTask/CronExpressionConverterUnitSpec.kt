package io.whozoss.agentos.scheduledTask

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.web.server.ResponseStatusException

/**
 * Unit tests for [CronExpressionConverter].
 *
 * Covers the bidirectional conversion between `(frequency, dayOfWeek, timeUtc)` and
 * the 5-field cron string, as well as validation of invalid inputs.
 *
 * Note on timeUtc format: the strict `HH:mm` two-digit format (e.g. "09:00" not "9:00")
 * is enforced upstream by `@Pattern` on the DTO before the converter is ever called.
 * The converter only rejects inputs that cannot be parsed at all (no colon, non-numeric,
 * or out-of-range values).
 */
class CronExpressionConverterUnitSpec : StringSpec({

    // -------------------------------------------------------------------------
    // toCron -- DAILY
    // -------------------------------------------------------------------------

    "toCron DAILY 09:00 produces '0 9 * * *'" {
        CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "09:00") shouldBe "0 9 * * *"
    }

    "toCron DAILY 00:00 produces '0 0 * * *'" {
        CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "00:00") shouldBe "0 0 * * *"
    }

    "toCron DAILY 23:59 produces '59 23 * * *'" {
        CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "23:59") shouldBe "59 23 * * *"
    }

    "toCron DAILY ignores dayOfWeek when provided" {
        // dayOfWeek is irrelevant for DAILY -- no error, value ignored
        CronExpressionConverter.toCron(ScheduleFrequency.DAILY, "MON", "08:00") shouldBe "0 8 * * *"
    }

    // -------------------------------------------------------------------------
    // toCron -- WEEKLY
    // -------------------------------------------------------------------------

    "toCron WEEKLY MON 09:00 produces '0 9 * * MON'" {
        CronExpressionConverter.toCron(ScheduleFrequency.WEEKLY, "MON", "09:00") shouldBe "0 9 * * MON"
    }

    "toCron WEEKLY FRI 14:30 produces '30 14 * * FRI'" {
        CronExpressionConverter.toCron(ScheduleFrequency.WEEKLY, "FRI", "14:30") shouldBe "30 14 * * FRI"
    }

    "toCron WEEKLY normalises dayOfWeek to uppercase" {
        CronExpressionConverter.toCron(ScheduleFrequency.WEEKLY, "wed", "10:00") shouldBe "0 10 * * WED"
    }

    "toCron WEEKLY with null dayOfWeek throws 400" {
        val ex = shouldThrow<ResponseStatusException> {
            CronExpressionConverter.toCron(ScheduleFrequency.WEEKLY, null, "09:00")
        }
        ex.statusCode.value() shouldBe 400
    }

    "toCron WEEKLY with invalid dayOfWeek throws 400" {
        val ex = shouldThrow<ResponseStatusException> {
            CronExpressionConverter.toCron(ScheduleFrequency.WEEKLY, "MONDAY", "09:00")
        }
        ex.statusCode.value() shouldBe 400
    }

    // -------------------------------------------------------------------------
    // toCron -- invalid timeUtc
    // -------------------------------------------------------------------------

    "toCron with no colon in timeUtc throws 400" {
        val ex = shouldThrow<ResponseStatusException> {
            CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "0900")
        }
        ex.statusCode.value() shouldBe 400
    }

    "toCron with non-numeric hour throws 400" {
        val ex = shouldThrow<ResponseStatusException> {
            CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "nine:00")
        }
        ex.statusCode.value() shouldBe 400
    }

    "toCron with hour out of range throws 400" {
        val ex = shouldThrow<ResponseStatusException> {
            CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "25:00")
        }
        ex.statusCode.value() shouldBe 400
    }

    // -------------------------------------------------------------------------
    // fromCron -- DAILY
    // -------------------------------------------------------------------------

    "fromCron '0 9 * * *' produces DAILY null 09:00" {
        val result = CronExpressionConverter.fromCron("0 9 * * *")
        result.frequency shouldBe ScheduleFrequency.DAILY
        result.dayOfWeek shouldBe null
        result.timeUtc shouldBe "09:00"
    }

    "fromCron '0 0 * * *' produces DAILY null 00:00" {
        val result = CronExpressionConverter.fromCron("0 0 * * *")
        result.frequency shouldBe ScheduleFrequency.DAILY
        result.dayOfWeek shouldBe null
        result.timeUtc shouldBe "00:00"
    }

    "fromCron '59 23 * * *' produces DAILY null 23:59" {
        val result = CronExpressionConverter.fromCron("59 23 * * *")
        result.frequency shouldBe ScheduleFrequency.DAILY
        result.dayOfWeek shouldBe null
        result.timeUtc shouldBe "23:59"
    }

    // -------------------------------------------------------------------------
    // fromCron -- WEEKLY
    // -------------------------------------------------------------------------

    "fromCron '0 9 * * MON' produces WEEKLY MON 09:00" {
        val result = CronExpressionConverter.fromCron("0 9 * * MON")
        result.frequency shouldBe ScheduleFrequency.WEEKLY
        result.dayOfWeek shouldBe "MON"
        result.timeUtc shouldBe "09:00"
    }

    "fromCron '30 14 * * FRI' produces WEEKLY FRI 14:30" {
        val result = CronExpressionConverter.fromCron("30 14 * * FRI")
        result.frequency shouldBe ScheduleFrequency.WEEKLY
        result.dayOfWeek shouldBe "FRI"
        result.timeUtc shouldBe "14:30"
    }

    // -------------------------------------------------------------------------
    // round-trip
    // -------------------------------------------------------------------------

    "toCron then fromCron round-trips DAILY" {
        val cron = CronExpressionConverter.toCron(ScheduleFrequency.DAILY, null, "08:30")
        val back = CronExpressionConverter.fromCron(cron)
        back.frequency shouldBe ScheduleFrequency.DAILY
        back.dayOfWeek shouldBe null
        back.timeUtc shouldBe "08:30"
    }

    "toCron then fromCron round-trips WEEKLY" {
        val cron = CronExpressionConverter.toCron(ScheduleFrequency.WEEKLY, "THU", "16:45")
        val back = CronExpressionConverter.fromCron(cron)
        back.frequency shouldBe ScheduleFrequency.WEEKLY
        back.dayOfWeek shouldBe "THU"
        back.timeUtc shouldBe "16:45"
    }

    // -------------------------------------------------------------------------
    // fromCron -- invalid inputs
    // -------------------------------------------------------------------------

    "fromCron with unknown day-of-week throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            CronExpressionConverter.fromCron("0 9 * * MONDAY")
        }
    }

    "fromCron with wrong field count throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            CronExpressionConverter.fromCron("0 9 * *")
        }
    }
})
