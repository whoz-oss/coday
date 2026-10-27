package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Schema

data class ConfigFieldChangeDto(
    val path: String,
    @field:Schema(description = "Current value, masked; null when absent or not visible to the viewer.")
    val before: JsonNode?,
    val after: JsonNode?,
    val remove: Boolean,
)
