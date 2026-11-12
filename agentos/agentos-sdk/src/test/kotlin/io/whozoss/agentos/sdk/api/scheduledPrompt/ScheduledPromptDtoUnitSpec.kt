package io.whozoss.agentos.sdk.api.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Unit tests for the [ScheduledPromptDto] loop-mode cross-field `@AssertTrue` constraint.
 *
 * Same approach as [PlanningDtoUnitSpec]: assert on the computed boolean property, since
 * agentos-sdk has no Bean Validation implementation on its test classpath.
 */
class ScheduledPromptDtoUnitSpec : StringSpec({

    fun dto(
        agentLoopName: String? = null,
        loopConfiguration: LoopConfigurationDto? = null,
    ) = ScheduledPromptDto(
        agentConfigId = UUID.randomUUID(),
        promptContent = "prompt",
        name = "name",
        recurrence = RecurrenceDto(unit = SchedulerUnit.WEEK, timeUtc = LocalTime.of(8, 0)),
        planning = PlanningDto(startDate = LocalDate.of(2026, 1, 1), endType = SchedulerEndType.NEVER),
        agentLoopName = agentLoopName,
        loopConfiguration = loopConfiguration,
    )

    val loopConfiguration = LoopConfigurationDto(searchToolName = "WHOZ__SearchTalents")

    "historical mode (no loop field) is consistent" {
        dto().isLoopModeConsistent.shouldBeTrue()
    }

    "loop mode (both fields set) is consistent" {
        dto(agentLoopName = "Looper", loopConfiguration = loopConfiguration).isLoopModeConsistent.shouldBeTrue()
    }

    "agentLoopName without loopConfiguration is inconsistent" {
        dto(agentLoopName = "Looper").isLoopModeConsistent.shouldBeFalse()
    }

    "loopConfiguration without agentLoopName is inconsistent" {
        dto(loopConfiguration = loopConfiguration).isLoopModeConsistent.shouldBeFalse()
    }

    "blank agentLoopName counts as absent" {
        dto(agentLoopName = "  ").isLoopModeConsistent.shouldBeTrue()
        dto(agentLoopName = "  ", loopConfiguration = loopConfiguration).isLoopModeConsistent.shouldBeFalse()
    }
})
