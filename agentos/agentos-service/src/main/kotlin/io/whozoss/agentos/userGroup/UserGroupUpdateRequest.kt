package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.*

@Schema(name = "UserGroupUpdateRequest")
data class UserGroupUpdateRequest(
    @field:NotBlank
    @field:Size(max = 250)
    val name: String,
    @field:Size(max = 200)
    val addedUserExternalIds: List<String> = emptyList(),
    @field:Size(max = 200)
    val removedUserExternalIds: List<String> = emptyList(),
    @field:Size(max = 200)
    val agentIds: List<UUID> = emptyList(),
)
