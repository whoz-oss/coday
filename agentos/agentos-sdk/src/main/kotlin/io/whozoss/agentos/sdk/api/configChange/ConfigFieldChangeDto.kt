package io.whozoss.agentos.sdk.api.configChange

import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode

data class ConfigFieldChangeDto(
    val path: String,
    @field:Schema(description = "Current value, masked; null when absent or not visible to the viewer.")
    val before: JsonNode?,
    val after: JsonNode?,
    val remove: Boolean,
)
