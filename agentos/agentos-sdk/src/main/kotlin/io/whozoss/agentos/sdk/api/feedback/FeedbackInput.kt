package io.whozoss.agentos.sdk.api.feedback

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
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
    @field:NotNull(message = "caseEventId must not be null")
    val caseEventId: UUID,
    @field:NotNull(message = "positive must not be null")
    val positive: Boolean,
    @field:Schema(description = "Optional category label. Ignored (cleared) when positive=true.")
    val type: String? = null,
    @field:Schema(description = "Optional free-text note. Ignored (cleared) when positive=true.")
    val comment: String? = null,
)
