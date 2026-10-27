package io.whozoss.agentos.configAgent

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ConfigChangeReportDto(
    val decision: ConfigChangeDecision,
    val appliedBy: UUID,
    val results: List<ConfigOperationResultDto>,
    @field:Schema(description = "False when the configuration was written but the agent could not be told; applying again re-sends it.")
    val answerPosted: Boolean = true,
)
