package io.whozoss.agentos.configAgent

import java.util.UUID

data class ConfigOperationResultDto(
    val ref: String,
    val status: ConfigResultStatus,
    val entityId: UUID? = null,
    val message: String? = null,
)
