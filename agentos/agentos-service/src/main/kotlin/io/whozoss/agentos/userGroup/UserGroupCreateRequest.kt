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
    val userIds: List<String> = emptyList(),
    val agentIds: List<UUID> = emptyList(),
)
