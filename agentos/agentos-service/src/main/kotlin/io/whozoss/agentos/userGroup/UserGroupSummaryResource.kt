package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(name = "UserGroupSummary")
data class UserGroupSummaryResource(
    val id: UUID,
    val name: String,
)

fun UserGroupSummary.toResource() = UserGroupSummaryResource(id = id, name = name)
