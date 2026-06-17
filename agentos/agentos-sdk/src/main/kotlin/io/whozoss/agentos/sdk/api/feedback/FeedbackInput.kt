package io.whozoss.agentos.sdk.api.feedback

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * Request body for `POST /api/feedbacks`.
 *
 * Creates or updates (upserts) feedback on a CaseEvent. The server resolves
 * [namespaceId] and [caseId] from the referenced [caseEventId] — clients must
 * not supply those fields.
 *
 * **Business rule:** when [positive] is `true`, [type] and [comment] are cleared by
 * the server before persistence. Positive feedback does not carry a reason.
 *
 * @property caseEventId The ID of the CaseEvent to attach feedback to. Required.
 * @property positive `true` for thumbs-up, `false` for thumbs-down. Required.
 * @property type Optional category label. Ignored (cleared) when [positive] is true.
 * @property comment Optional free-text note. Ignored (cleared) when [positive] is true.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeedbackInput(
    val caseEventId: UUID,
    val positive: Boolean,
    val type: String? = null,
    val comment: String? = null,
)
