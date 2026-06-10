package io.whozoss.agentos.feedback

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * Outbound DTO for [io.whozoss.agentos.sdk.feedback.Feedback] responses.
 *
 * All fields are server-set — this class is never used as a request body.
 * Use [FeedbackInput] for create/update requests.
 */
@Schema(name = "Feedback")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeedbackResource(
    val id: UUID,
    val namespaceId: UUID,
    val caseId: UUID,
    val caseEventId: UUID,
    val positive: Boolean,
    val type: String? = null,
    val comment: String? = null,
    val timestamp: Instant,
    val createdBy: String? = null,
    val createdOn: Instant,
    val updatedBy: String? = null,
    val updatedOn: Instant,
)
