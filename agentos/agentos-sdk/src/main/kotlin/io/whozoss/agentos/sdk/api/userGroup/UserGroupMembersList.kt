package io.whozoss.agentos.sdk.api.userGroup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.api.common.ListMetadata

/** Response of the members list; `metadata.totalCount` is the number of non-removed members of the group. */
@Schema(name = "UserGroupMembersListResponse")
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserGroupMembersListResponse(
    val data: List<UserGroupMember>,
    val metadata: ListMetadata,
)
