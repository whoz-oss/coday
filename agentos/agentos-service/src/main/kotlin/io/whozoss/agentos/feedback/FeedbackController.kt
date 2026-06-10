package io.whozoss.agentos.feedback

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
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
 * REST API for [io.whozoss.agentos.sdk.feedback.Feedback] entities.
 *
 * Authorization piggybacks on the parent [io.whozoss.agentos.caseFlow.Case] READ
 * permission: if you can read the case, you can submit feedback on its events.
 * This keeps authorization simple and consistent with [io.whozoss.agentos.caseEvent.CaseEventRestController].
 *
 * Feedback is upserted per (user, caseEvent) pair: the first POST creates a node;
 * subsequent POSTs from the same user on the same event update it in-place.
 * This allows the UI to send an initial thumbs-up/down and then enrich it with
 * a reason and comment without creating duplicates.
 */
@RestController
@RequestMapping("/api/feedbacks", produces = [MediaType.APPLICATION_JSON_VALUE])
class FeedbackController(
    private val feedbackService: FeedbackService,
    private val caseEventService: CaseEventService,
) {
    /**
     * POST /api/feedbacks — upsert feedback on a case event.
     *
     * Validates that the target case event exists, resolves [namespaceId] from it,
     * then delegates to [FeedbackService.upsert]. If the authenticated user already
     * has a feedback node for this event it is updated in-place; otherwise a new
     * node is created.
     * Authorization: READ on the parent Case.
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#input.caseId, 'Case', 'READ')")
    fun create(
        @Valid @RequestBody input: FeedbackInput,
    ): FeedbackResource {
        val event =
            caseEventService.findById(input.caseEventId)
                ?: throw ResourceNotFoundException("CaseEvent not found: ${input.caseEventId}")
        return toResource(feedbackService.upsert(toDomain(input, namespaceId = event.namespaceId)))
    }

    /**
     * GET /api/feedbacks/by-parentId/{caseId} — list all feedback for a case.
     *
     * Authorization: READ on the Case.
     */
    @GetMapping("/by-parentId/{caseId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun listByCase(
        @PathVariable caseId: UUID,
    ): List<FeedbackResource> = feedbackService.findByParent(caseId).map { toResource(it) }

    /**
     * GET /api/feedbacks/by-case-event/{caseEventId} — list all feedback on a specific event.
     *
     * Authorization: READ on the parent Case, resolved via [CaseEventService].
     */
    @GetMapping("/by-case-event/{caseEventId}")
    @PreAuthorize("@caseEventGuard.canRead(#caseEventId)")
    fun listByCaseEvent(
        @PathVariable caseEventId: UUID,
    ): List<FeedbackResource> = feedbackService.findByCaseEventId(caseEventId).map { toResource(it) }

    private fun toResource(feedback: Feedback): FeedbackResource =
        FeedbackResource(
            id = feedback.metadata.id,
            namespaceId = feedback.namespaceId,
            caseId = feedback.caseId,
            caseEventId = feedback.caseEventId,
            positive = feedback.positive,
            type = feedback.type,
            comment = feedback.comment,
            timestamp = feedback.timestamp,
            createdBy = feedback.metadata.createdBy,
            createdOn = feedback.metadata.created,
            updatedBy = feedback.metadata.modifiedBy,
            updatedOn = feedback.metadata.modified,
        )

    private fun toDomain(
        input: FeedbackInput,
        namespaceId: UUID,
    ): Feedback =
        Feedback(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = input.caseId,
            caseEventId = input.caseEventId,
            positive = input.positive,
            type = input.type,
            comment = input.comment,
        )

    companion object : KLogging()
}
