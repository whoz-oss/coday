package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.*

@Schema(name = "UserGroupCreateRequest")
data class UserGroupCreateRequest(
    @field:NotBlank
    val namespaceExternalId: String,
    @field:NotBlank
    @field:Size(max = 250)
    val name: String,
    @field:Size(max = 200)
    val userIds: List<String> = emptyList(),
    @field:Size(max = 200)
    val agentIds: List<UUID> = emptyList(),
)
