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
    /**
     * Per-user favorite flag. Populated by the case-list endpoints;
     * single-case fetches (get/create/update) return the default `false`.
     */
    val favorite: Boolean = false,
    /**
     * The caller's direct relation on this case (`ADMIN` or `MEMBER`), or `null` when the
     * caller has no direct edge (e.g. transitive namespace-admin access). Populated by the
     * case-list endpoints; lets the UI gate ADMIN-only actions (delete). Null on single-case fetches.
     */
    @field:Schema(description = "The caller's direct relation on this case", allowableValues = ["ADMIN", "MEMBER"])
    val role: String? = null,
)
