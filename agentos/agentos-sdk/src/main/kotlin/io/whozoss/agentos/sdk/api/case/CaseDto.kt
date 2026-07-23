package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/**
 * HTTP DTO for Case entities — used as both request body and response body on
 * the `/api/cases` endpoints.
 *
 * This is the canonical type shared between agentos-service and external consumers
 * (e.g. the Copilot module in the whoz repo). Validation annotations (`@NotNull`) are
 * `compileOnly` in the SDK — they are only active when jakarta.validation-api is on
 * the classpath at runtime (which it is in agentos-service via Spring Boot).
 *
 * @property id Server-assigned UUID. Null on create requests; always present in responses.
 * @property namespaceId The namespace this case belongs to. Required on create.
 * @property status Current lifecycle status. Defaults to [CaseStatus.PENDING] on create.
 * @property title Optional human-readable title.
 * @property created Server-set creation timestamp. Present in responses only.
 * @property modified Server-set last-modification timestamp. Present in responses only.
 * @property removed Soft-delete flag. False by default.
 * @property favorite Per-user favorite flag. Populated by list endpoints; defaults to false on single-case fetches.
 * @property role The caller's direct relation on this case (`ADMIN` or `MEMBER`), or null when the
 *   caller has no direct edge (e.g. transitive namespace-admin access). Populated by list endpoints;
 *   null on single-case fetches.
 */
@Schema(name = "Case")
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseDto(
    val id: UUID? = null,
    @field:NotNull(message = "namespaceId must not be null")
    val namespaceId: UUID,
    val status: CaseStatus = CaseStatus.PENDING,
    val title: String? = null,
    val parentCaseId: UUID? = null,
    val created: Instant? = null,
    val modified: Instant? = null,
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
