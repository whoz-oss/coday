package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.api.common.CursorPagination
import io.whozoss.agentos.sdk.api.common.ListMetadata
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** Request body of `POST /api/user-groups/{userGroupId}/members/list`: members sorted by `externalId` ascending. */
@Schema(name = "UserGroupMembersListRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupMembersListRequest(
    @field:Schema(description = "Page size, ${CursorPagination.DEFAULT_LIMIT} when omitted", nullable = true)
    @field:Min(1)
    @field:Max(CursorPagination.MAX_LIMIT)
    val limit: Int? = null,
    @field:Schema(description = "Opaque cursor: the `metadata.next` of the previous response; omit for the first page", nullable = true)
    val next: String? = null,
)

/** Response of the members list; `metadata.totalCount` is the number of non-removed members of the group. */
@Schema(name = "UserGroupMembersListResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupMembersListResponse(
    val data: List<UserGroupMember>,
    val metadata: ListMetadata,
)
