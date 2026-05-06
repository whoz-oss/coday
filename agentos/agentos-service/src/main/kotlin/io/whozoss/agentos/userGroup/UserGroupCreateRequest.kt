package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(name = "UserGroupCreateRequest")
data class UserGroupCreateRequest(
    @field:NotBlank
    val namespaceExternalId: String,
    @field:NotBlank
    @field:Size(max = 250)
    val name: String,
    // NOTE: membership (users + agents) is not yet implemented. The previous fields
    // `userIds` and `agentIds` were silently ignored on the server and have been removed
    // from the contract to avoid the client believing the server persisted them. Adding
    // membership is tracked as a separate epic.
)
