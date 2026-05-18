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
    val userExternalIdsToAdd: Set<@NotBlank String> = emptySet(),
    @field:Size(max = 200)
    val userExternalIdsToRemove: Set<@NotBlank String> = emptySet(),
    @field:Size(max = 200)
    val agentIds: Set<UUID> = emptySet(),
)
