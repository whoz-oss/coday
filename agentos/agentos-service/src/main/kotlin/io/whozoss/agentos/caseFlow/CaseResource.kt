package io.whozoss.agentos.caseFlow

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * HTTP resource (DTO) for [Case] entities.
 *
 * Annotated with @Schema(name = "Case") so that the generated OpenAPI spec
 * keeps the schema name "Case" instead of "CaseResource".
 */
@Schema(name = "Case")
data class CaseResource(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    val status: CaseStatus = CaseStatus.PENDING,
    val title: String? = null,
    val created: Instant? = null,
    val removed: Boolean = false,
    val favorite: Boolean = false,
)
