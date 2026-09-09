package io.whozoss.agentos.sdk.api.feedback

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * HTTP response DTO for Feedback entities — returned by the `/api/feedbacks` endpoints.
 *
 * All fields are server-set — this class is never used as a request body.
 * Use [FeedbackInput] for create/update requests.
 *
 * @property id Server-assigned UUID.
 * @property namespaceId The namespace the parent Case belongs to.
 * @property caseId The Case this feedback is attached to.
 * @property caseEventId The specific CaseEvent this feedback targets.
 * @property positive `true` for thumbs-up, `false` for thumbs-down.
 * @property type Optional category label. Always null when [positive] is true.
 * @property comment Optional free-text note. Always null when [positive] is true.
 * @property createdBy External ID of the user who submitted the feedback.
 * @property createdOn Server-set creation timestamp.
 * @property updatedBy External ID of the last user to update the feedback.
 * @property updatedOn Server-set last-update timestamp.
 */
@Schema(name = "Feedback")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeedbackDto(
    val id: UUID,
    val namespaceId: UUID,
    val caseId: UUID,
    val caseEventId: UUID,
    val positive: Boolean,
    val type: String? = null,
    val comment: String? = null,
    val createdBy: String? = null,
    val createdOn: Instant,
    val updatedBy: String? = null,
    val updatedOn: Instant,
)
