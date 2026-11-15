package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import java.util.UUID

/** The controller checks the question and hands it over: the answering is [CaseSideQuestionService]'s own spec. */
class CaseSideQuestionControllerUnitSpec :
    StringSpec({
        val caseId = UUID.randomUUID()

        "hands the trimmed question to the service and answers its answer" {
            val service = mockk<CaseSideQuestionService> { every { answer(caseId, "What is the plan?") } returns "The plan is X." }

            val response = CaseSideQuestionController(service).askSideQuestion(caseId, SideQuestionRequest("  What is the plan?  "))

            response shouldBe SideQuestionResponse(answer = "The plan is X.")
        }

        "refuses a blank question with a 400, without calling the service" {
            val service = mockk<CaseSideQuestionService>()

            listOf("", "   ", "\n\t").forEach { blank ->
                shouldThrow<BadRequestException> {
                    CaseSideQuestionController(service).askSideQuestion(caseId, SideQuestionRequest(blank))
                }
            }

            verify(exactly = 0) { service.answer(any(), any()) }
        }

        "accepts a question of exactly the maximum length, and refuses one character more" {
            val limit = CaseSideQuestionController.MAX_QUESTION_LENGTH
            val service = mockk<CaseSideQuestionService> { every { answer(any(), any()) } returns "ok" }
            val controller = CaseSideQuestionController(service)

            controller.askSideQuestion(caseId, SideQuestionRequest("a".repeat(limit))).answer shouldBe "ok"

            shouldThrow<BadRequestException> {
                controller.askSideQuestion(caseId, SideQuestionRequest("a".repeat(limit + 1)))
            }
            verify(exactly = 1) { service.answer(any(), any()) }
        }
    })
