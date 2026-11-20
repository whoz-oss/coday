package io.whozoss.agentos.caseFlow

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Body of `POST /api/cases/{caseId}/side-questions`. */
@Schema(name = "SideQuestionRequest")
data class SideQuestionRequest(
    @field:Schema(
        description = "The question, asked about the conversation of the case. Not blank, at most 4000 characters.",
        minLength = 1,
        maxLength = CaseSideQuestionController.MAX_QUESTION_LENGTH,
    )
    val question: String,
)

/** Response of `POST /api/cases/{caseId}/side-questions`. */
@Schema(name = "SideQuestionResponse")
data class SideQuestionResponse(
    @field:Schema(description = "The answer, in Markdown. It is not saved anywhere.")
    val answer: String,
)

/**
 * REST entry point of the side questions (`/btw`): a quick question about a case, answered from its
 * conversation, that is neither added to its conversation nor broadcast and never interrupts its run.
 * The work is done by [CaseSideQuestionService]; this class declares the route, checks the access and the
 * question.
 *
 * A standalone controller, and deliberately not part of `CaseApi`: adding an abstract method to an interface
 * that external consumers implement would break them at startup (same reasoning as
 * [io.whozoss.agentos.transcription.VoiceMessageController]).
 *
 * WRITE on the case is needed, as to post a message: the question spends the budget of the case (its usage is
 * recorded on it), even though nothing is added to its conversation. As for posting a message, a caller without
 * it is answered 403.
 */
@RestController
class CaseSideQuestionController(
    private val sideQuestionService: CaseSideQuestionService,
    private val userService: UserService,
) {
    @Operation(
        summary = "Ask a side question about a case",
        description =
            "POST /api/cases/{caseId}/side-questions: answers a quick question from the conversation of the case, " +
                "without running any tool. Nothing is added to the conversation of the case or broadcast, and a " +
                "running turn is not interrupted; its LLM usage is recorded on the case and counts toward its run " +
                "cost, so it needs WRITE on the case. 400 for a blank or too long question, 409 when the namespace " +
                "has no default AI model or the case is stopped, waiting for a cost confirmation or at its run cost " +
                "limit, 502 when the model fails to answer.",
    )
    @PostMapping(
        "/api/cases/{caseId}/side-questions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun askSideQuestion(
        @PathVariable caseId: UUID,
        @RequestBody request: SideQuestionRequest,
    ): SideQuestionResponse {
        val question = request.question.trim()
        if (question.isEmpty()) throw BadRequestException("The question must not be blank")
        if (question.length > MAX_QUESTION_LENGTH) {
            throw BadRequestException("The question must not exceed $MAX_QUESTION_LENGTH characters")
        }
        // The question is the user's text: it stays out of the logs.
        logger.info { "Side question asked on case $caseId (${question.length} chars)" }
        val userId = userService.getCurrentUser().id
        return SideQuestionResponse(answer = sideQuestionService.answer(caseId, question, userId))
    }

    companion object : KLogging() {
        const val MAX_QUESTION_LENGTH = 4000
    }
}
