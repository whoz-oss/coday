package io.whozoss.agentos.sdk.api.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.api.common.CursorPagination
import io.whozoss.agentos.sdk.api.common.ListMetadata
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID

/** Request body of `POST /api/users/list` (SUPER_ADMIN only): active users sorted by `externalId` ascending. */
@Schema(name = "UsersListRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UsersListRequest(
    @field:Schema(description = "Page size, ${CursorPagination.DEFAULT_LIMIT} when omitted", nullable = true)
    @field:Min(1)
    @field:Max(CursorPagination.MAX_LIMIT)
    val limit: Int? = null,
    @field:Schema(description = "Opaque cursor: the `metadata.next` of the previous response; omit for the first page", nullable = true)
    val next: String? = null,
    val filters: UsersListFilters? = null,
)

/** Filters of [UsersListRequest]; every filter that is set applies. */
@Schema(name = "UsersListFilters")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UsersListFilters(
    @field:Schema(description = "Only users with a direct ADMIN or MEMBER relation on this (non-removed) namespace", nullable = true)
    val namespaceId: UUID? = null,
    @field:Schema(
        description = "Case-insensitive 'contains' on email, externalId, firstname, lastname and the full name " +
            "('firstname lastname' or 'lastname firstname'), matched literally; blank means no filter",
        nullable = true,
    )
    val query: String? = null,
)

/** Response of the users list; `metadata.totalCount` respects the filters. */
@Schema(name = "UsersListResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UsersListResponse(
    val data: List<UserDto>,
    val metadata: ListMetadata,
)
