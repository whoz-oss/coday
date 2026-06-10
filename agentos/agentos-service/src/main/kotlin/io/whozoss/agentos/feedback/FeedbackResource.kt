package io.whozoss.agentos.feedback

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * HTTP resource (DTO) for [io.whozoss.agentos.sdk.feedback.Feedback] entities.
 *
 * Annotated with @Schema(name = "Feedback") so that the generated OpenAPI spec
 * keeps the schema name "Feedback" instead of "FeedbackResource".
 *
 * [caseId] and [caseEventId] are required — feedback must always reference a specific
 * case event. [namespaceId] is required for multi-tenant scoping.
 * [type] and [comment] are optional free-form fields.
 */
@Schema(name = "Feedback")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeedbackResource(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    @field:NotNull(message = "caseId must not be null")
    val caseId: UUID,
    @field:NotNull(message = "caseEventId must not be null")
    val caseEventId: UUID,
    @field:NotNull(message = "positive must not be null")
    val positive: Boolean,
    val type: String? = null,
    val comment: String? = null,
    val timestamp: Instant? = null,
    val createdBy: String? = null,
    val createdOn: Instant? = null,
    val updatedBy: String? = null,
    val updatedOn: Instant? = null,
)
