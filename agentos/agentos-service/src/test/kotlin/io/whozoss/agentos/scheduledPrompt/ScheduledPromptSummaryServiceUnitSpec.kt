package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.aiModel.ModelResolver
import io.whozoss.agentos.generation.GenerationTimeoutException
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.generation.StructuredLlmResult
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Unit tests for [ScheduledPromptSummaryService]. The LLM call is mocked at [StructuredLlmClient]. */
class ScheduledPromptSummaryServiceUnitSpec :
    StringSpec({

        val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        val modelResolver = mockk<ModelResolver>()
        val client = mockk<StructuredLlmClient>()
        val service = ScheduledPromptSummaryService(modelResolver, client, objectMapper)

        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val input =
            ScheduledPromptSummaryService.SummaryInput(
                description = "Je voudrais la météo du staffing",
                recurrence =
                    RecurrenceDto(
                        unit = SchedulerUnit.WEEK,
                        days = listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                        timeUtc = LocalTime.of(8, 0),
                    ),
                planning = PlanningDto(startDate = LocalDate.of(2026, 11, 17), endType = SchedulerEndType.NEVER),
                targetAgentName = "Bench Optimizer",
                targetAgentDescription = "Finds the best missions for talents on the bench",
                prompt = "Find the best matching missions and tell the manager.",
                searchTool = "SearchTalents",
                searchToolDescription = "Searches talents",
                searchParams = objectMapper.readTree("""{"availability": ["BENCH"]}"""),
            )

        fun answer(json: String?) = StructuredLlmResult(json = json?.let { objectMapper.readTree(it) }, rawText = json)

        beforeTest {
            clearAllMocks()
            every { modelResolver.resolve(namespaceId, userId) } returns (mockk<AiModel>(relaxed = true) to mockk<AiProvider>(relaxed = true))
        }

        "returns the summary, asked from the facts and in the requested language" {
            val system = slot<String>()
            val facts = slot<String>()
            coEvery { client.call(any(), any(), capture(system), capture(facts)) } returns
                answer("""{"summary": " **Every week**, *Bench Optimizer* will: ... "}""")

            val outcome = service.summarize(namespaceId, userId, input, "en")

            outcome.summary shouldBe "**Every week**, *Bench Optimizer* will: ..."
            outcome.warning.shouldBeNull()
            system.captured shouldContain "BCP 47 tag 'en'"
            listOf("Bench Optimizer", "BENCH", "MONDAY", "NEVER", "Find the best matching missions").forEach {
                facts.captured shouldContain it
            }
        }

        "without a preferred language, it writes in the language of the request" {
            val system = slot<String>()
            coEvery { client.call(any(), any(), capture(system), any()) } returns answer("""{"summary": "résumé"}""")

            service.summarize(namespaceId, userId, input, null)

            system.captured shouldContain "language of the user's original request"
        }

        "an unusable answer gives no summary and a warning" {
            forAll(
                row<String?>(null),
                row<String?>("""{"other": "x"}"""),
                row<String?>("""{"summary": "   "}"""),
                row<String?>("""{"summary": 42}"""),
            ) { json ->
                coEvery { client.call(any(), any(), any(), any()) } returns answer(json)

                val outcome = service.summarize(namespaceId, userId, input, "en")

                outcome.summary.shouldBeNull()
                outcome.warning.shouldNotBeNull()
            }
        }

        "no model, or a failed or timed-out call, gives no summary and a warning instead of an error" {
            every { modelResolver.resolve(namespaceId, userId) } returns null
            service.summarize(namespaceId, userId, input, "en").warning!! shouldContain "no AI model"

            every { modelResolver.resolve(namespaceId, userId) } returns (mockk<AiModel>(relaxed = true) to mockk<AiProvider>(relaxed = true))
            forAll(
                row<Exception>(IllegalStateException("provider down")),
                row<Exception>(GenerationTimeoutException("slow")),
            ) { failure ->
                coEvery { client.call(any(), any(), any(), any()) } throws failure

                val outcome = service.summarize(namespaceId, userId, input, "en")

                outcome.summary.shouldBeNull()
                outcome.warning shouldBe "No summary could be generated."
            }
        }
    })
