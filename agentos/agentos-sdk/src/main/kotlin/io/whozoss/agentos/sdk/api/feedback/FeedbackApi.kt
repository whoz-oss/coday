package io.whozoss.agentos.sdk.api.feedback

import java.util.UUID

/**
 * HTTP API contract for Feedback entities.
 *
 * Implemented by `FeedbackController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * Feedback is upserted per (user, caseEvent) pair: the first POST creates a node;
 * subsequent POSTs from the same user on the same event update it in-place.
 *
 * Authorization piggybacks on the parent Case READ permission: if you can read
 * the case, you can submit and read feedback on its events.
 */
interface FeedbackApi {

    /**
     * POST /api/feedbacks — upsert feedback on a case event.
     *
     * [namespaceId] and [caseId] are server-resolved from the referenced [FeedbackInput.caseEventId].
     * When [FeedbackInput.positive] is true, [FeedbackInput.type] and [FeedbackInput.comment]
     * are cleared by the server before persistence.
     */
    fun create(input: FeedbackInput): FeedbackDto

    /** GET /api/feedbacks/by-parentId/{caseId} — list all feedback for a case. */
    fun listByCase(caseId: UUID): List<FeedbackDto>

    /** GET /api/feedbacks/by-case-event/{caseEventId} — list all feedback on a specific event. */
    fun listByCaseEvent(caseEventId: UUID): List<FeedbackDto>
}
