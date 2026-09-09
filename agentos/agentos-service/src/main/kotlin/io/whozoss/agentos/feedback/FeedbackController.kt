package io.whozoss.agentos.feedback

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.api.feedback.FeedbackApi
import io.whozoss.agentos.sdk.api.feedback.FeedbackDto
import io.whozoss.agentos.sdk.api.feedback.FeedbackInput
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.feedback.Feedback
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for [Feedback] entities. Implements [FeedbackApi] so external consumers
 * can declare a Feign client against the SDK interface.
 */
@RestController
@RequestMapping("/api/feedbacks", produces = [MediaType.APPLICATION_JSON_VALUE])
class FeedbackController(
    private val feedbackService: FeedbackService,
    private val caseEventService: CaseEventService,
) : FeedbackApi {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@caseEventGuard.canRead(#input.caseEventId)")
    override fun create(@Valid @RequestBody input: FeedbackInput): FeedbackDto {
        val event = caseEventService.findById(input.caseEventId)
            ?: throw ResourceNotFoundException("CaseEvent not found: ${input.caseEventId}")
        return feedbackService.upsert(
            Feedback(
                metadata = EntityMetadata(),
                namespaceId = event.namespaceId,
                caseId = event.caseId,
                caseEventId = input.caseEventId,
                positive = input.positive,
                type = input.type,
                comment = input.comment,
            )
        ).toDto()
    }

    @GetMapping("/by-parentId/{caseId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    override fun listByCase(@PathVariable caseId: UUID): List<FeedbackDto> =
        feedbackService.findByParent(caseId).map { it.toDto() }

    @GetMapping("/by-case-event/{caseEventId}")
    @PreAuthorize("@caseEventGuard.canRead(#caseEventId)")
    override fun listByCaseEvent(@PathVariable caseEventId: UUID): List<FeedbackDto> =
        feedbackService.findByCaseEventId(caseEventId).map { it.toDto() }

    companion object : KLogging()
}

// ---------------------------------------------------------------------------
// Extension: Feedback → FeedbackDto
// ---------------------------------------------------------------------------

private fun Feedback.toDto() = FeedbackDto(
    id = metadata.id,
    namespaceId = namespaceId,
    caseId = caseId,
    caseEventId = caseEventId,
    positive = positive,
    type = type,
    comment = comment,
    createdBy = metadata.createdBy,
    createdOn = metadata.created,
    updatedBy = metadata.modifiedBy,
    updatedOn = metadata.modified,
)
