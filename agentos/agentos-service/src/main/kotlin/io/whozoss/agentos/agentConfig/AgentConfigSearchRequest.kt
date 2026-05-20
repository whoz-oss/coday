package io.whozoss.agentos.agentConfig

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(name = "AgentConfigSearchRequest")
data class AgentConfigSearchRequest(
    @field:NotBlank
    val namespaceExternalId: String,
    @field:NotBlank
    val userExternalId: String,
)
