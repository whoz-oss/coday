package io.whozoss.agentos.agentConfig

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.util.UUID

@Schema(name = "AgentConfigSearchRequest")
data class AgentConfigSearchRequest(
    val namespaceId: UUID,
    @field:NotBlank
    val userExternalId: String,
    // preparation for later coday autocomplete on agent names
    val userId: String? = null,
)
