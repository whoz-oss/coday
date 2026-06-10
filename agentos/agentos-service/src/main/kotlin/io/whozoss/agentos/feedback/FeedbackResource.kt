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
 *
 * [timestamp], [createdBy], [createdOn], [updatedBy], and [updatedOn] are
 * server-set audit fields included in responses but ignored on inbound requests.
 */
@Schema(name = "Feedback")
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FeedbackResource(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    @field:Schema(description = "Namespace of the parent case. Accepted on input but overridden server-side from the resolved CaseEvent.")
    val namespaceId: UUID,
    @field:NotNull(message = "caseId must not be null")
    val caseId: UUID,
    @field:NotNull(message = "caseEventId must not be null")
    val caseEventId: UUID,
    @field:NotNull(message = "positive must not be null")
    val positive: Boolean,
    @field:Schema(description = "Optional category label. Ignored (cleared) when positive=true.")
    val type: String? = null,
    @field:Schema(description = "Optional free-text note. Ignored (cleared) when positive=true.")
    val comment: String? = null,
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    val timestamp: Instant? = null,
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    val createdBy: String? = null,
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    val createdOn: Instant? = null,
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    val updatedBy: String? = null,
    @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    val updatedOn: Instant? = null,
)
