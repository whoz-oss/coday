package io.whozoss.agentos.sdk.api.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.time.LocalDate

/**
 * Unit tests for the [PlanningDto] cross-field `@AssertTrue` constraints.
 *
 * These constraints replace the old `ScheduledPromptServiceImpl.validateEndCondition()`
 * runtime check (formerly throwing [io.whozoss.agentos.exception.BadRequestException]).
 * They are now Bean Validation constraints enforced by `@Valid` cascading from
 * `ScheduledPromptDto.planning` on the controller's create/update endpoints.
 *
 * We assert directly on the computed boolean properties rather than instantiating a
 * jakarta.validation Validator, since agentos-sdk only depends on the Validation API
 * as `compileOnly` (no Bean Validation implementation on the test classpath).
 */
class PlanningDtoUnitSpec : StringSpec({

    val today: LocalDate = LocalDate.of(2026, 1, 1)

    fun planningDto(
        startDate: LocalDate = today,
        endType: SchedulerEndType = SchedulerEndType.NEVER,
        endDate: LocalDate? = null,
        maxOccurrenceCount: Int? = null,
    ) = PlanningDto(startDate = startDate, endType = endType, endDate = endDate, maxOccurrenceCount = maxOccurrenceCount)

    // -------------------------------------------------------------------------
    // ON_DATE
    // -------------------------------------------------------------------------

    "ON_DATE: invalid when endDate is null" {
        planningDto(endType = SchedulerEndType.ON_DATE, endDate = null).isEndDateValidForOnDate.shouldBeFalse()
    }

    "ON_DATE: invalid when endDate equals startDate" {
        planningDto(endType = SchedulerEndType.ON_DATE, startDate = today, endDate = today).isEndDateValidForOnDate.shouldBeFalse()
    }

    "ON_DATE: invalid when endDate is before startDate" {
        planningDto(endType = SchedulerEndType.ON_DATE, startDate = today, endDate = today.minusDays(1)).isEndDateValidForOnDate.shouldBeFalse()
    }

    "ON_DATE: valid when endDate is after startDate" {
        planningDto(endType = SchedulerEndType.ON_DATE, startDate = today, endDate = today.plusDays(1)).isEndDateValidForOnDate.shouldBeTrue()
    }

    "ON_DATE: maxOccurrenceCount constraint is unaffected (always valid)" {
        planningDto(endType = SchedulerEndType.ON_DATE, endDate = today.plusDays(1)).isMaxOccurrenceCountValidForOccurrences.shouldBeTrue()
    }

    // -------------------------------------------------------------------------
    // OCCURRENCES
    // -------------------------------------------------------------------------

    "OCCURRENCES: invalid when maxOccurrenceCount is null" {
        planningDto(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = null).isMaxOccurrenceCountValidForOccurrences.shouldBeFalse()
    }

    "OCCURRENCES: valid when maxOccurrenceCount is set (positivity enforced separately by @Positive)" {
        planningDto(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = 1).isMaxOccurrenceCountValidForOccurrences.shouldBeTrue()
    }

    "OCCURRENCES: endDate constraint is unaffected (always valid)" {
        planningDto(endType = SchedulerEndType.OCCURRENCES, maxOccurrenceCount = 1).isEndDateValidForOnDate.shouldBeTrue()
    }

    // -------------------------------------------------------------------------
    // NEVER
    // -------------------------------------------------------------------------

    "NEVER: both constraints are always valid regardless of endDate/maxOccurrenceCount" {
        planningDto(endType = SchedulerEndType.NEVER).isEndDateValidForOnDate.shouldBeTrue()
        planningDto(endType = SchedulerEndType.NEVER).isMaxOccurrenceCountValidForOccurrences.shouldBeTrue()
    }
})
