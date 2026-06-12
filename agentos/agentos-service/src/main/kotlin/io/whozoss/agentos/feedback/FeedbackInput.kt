package io.whozoss.agentos.feedback

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * Inbound DTO for creating or updating a [io.whozoss.agentos.sdk.feedback.Feedback].
 *
 * Contains only the fields the client is allowed to supply.
 * [namespaceId] and [caseId] are intentionally absent — both are resolved server-side
 * from the fetched [io.whozoss.agentos.sdk.caseEvent.CaseEvent], preventing
 * cross-namespace and cross-case reference injection.
 * Audit fields ([id], timestamps, authorship) are server-set and never accepted on input.
 *
 * **Business rule:** when [positive] is `true`, [type] and [comment] are cleared by
 * [FeedbackService.upsert] before persistence. Positive feedback does not carry a reason.
 */
@Schema(name = "FeedbackInput")
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
