package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.nulls.shouldBeNull
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

/** Unit tests for [ScheduledPromptSummaryService]. The LLM call is mocked at [StructuredLlmClient.callText]. */
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

        val markdown =
            """
            **Every week, Monday to Friday**, *Bench Optimizer* will:
            - review each **staffable talent** currently on the bench
            - send their manager the best matching missions

            Uses the bench as the search scope.
            """.trimIndent()

        beforeTest {
            clearAllMocks()
            every { modelResolver.resolve(namespaceId, userId) } returns (mockk<AiModel>(relaxed = true) to mockk<AiProvider>(relaxed = true))
        }

        "returns the Markdown summary, asked as plain text from the facts and in the requested language" {
            val system = slot<String>()
            val facts = slot<String>()
            coEvery { client.callText(any(), any(), capture(system), capture(facts)) } returns markdown

            val outcome = service.summarize(namespaceId, userId, input, "en")

            outcome.summary shouldBe markdown
            outcome.warning.shouldBeNull()
            system.captured shouldContain "BCP 47 tag 'en'"
            system.captured shouldContain "Markdown only"
            listOf("Bench Optimizer", "BENCH", "MONDAY", "NEVER", "Find the best matching missions").forEach {
                facts.captured shouldContain it
            }
        }

        "without a preferred language, it writes in the language of the request" {
            val system = slot<String>()
            coEvery { client.callText(any(), any(), capture(system), any()) } returns markdown

            service.summarize(namespaceId, userId, input, null)

            system.captured shouldContain "language of the user's original request"
        }

        "the answer is normalized to Markdown: wrapping fence removed, other bullets and line endings unified" {
            forAll(
                row("```markdown\n$markdown\n```"),
                row("```\n$markdown\n```"),
                row(markdown.replace("\n", "\r\n")),
                row(markdown.replace("\n- ", "\n* ")),
                row(markdown.replace("\n- ", "\n• ")),
                row("  \n$markdown\n  "),
            ) { answer ->
                ScheduledPromptSummaryService.toMarkdown(answer) shouldBe markdown
            }
        }

        "an answer that is not Markdown of the expected shape gives no summary and a warning" {
            forAll(
                // flat text, as a model would write it squeezed into one line
                row("Every week, Bench Optimizer will review each staffable talent and send their manager missions."),
                // no bold opening
                row("Every week, Bench Optimizer will:\n- review talents\n- send missions"),
                // no bullet
                row("**Every week**, *Bench Optimizer* will review talents and send missions."),
            ) { answer ->
                coEvery { client.callText(any(), any(), any(), any()) } returns answer

                val outcome = service.summarize(namespaceId, userId, input, "en")

                outcome.summary.shouldBeNull()
                outcome.warning!! shouldContain "not Markdown"
            }
        }

        "no content gives no summary and a warning" {
            coEvery { client.callText(any(), any(), any(), any()) } returns null

            val outcome = service.summarize(namespaceId, userId, input, "en")

            outcome.summary.shouldBeNull()
            outcome.warning!! shouldContain "no content"
        }

        "no model, or a failed or timed-out call, gives no summary and a warning instead of an error" {
            every { modelResolver.resolve(namespaceId, userId) } returns null
            service.summarize(namespaceId, userId, input, "en").warning!! shouldContain "no AI model"

            every { modelResolver.resolve(namespaceId, userId) } returns (mockk<AiModel>(relaxed = true) to mockk<AiProvider>(relaxed = true))
            forAll(
                row<Exception>(IllegalStateException("provider down")),
                row<Exception>(GenerationTimeoutException("slow")),
            ) { failure ->
                coEvery { client.callText(any(), any(), any(), any()) } throws failure

                val outcome = service.summarize(namespaceId, userId, input, "en")

                outcome.summary.shouldBeNull()
                outcome.warning shouldBe "No summary could be generated."
            }
        }
    })
