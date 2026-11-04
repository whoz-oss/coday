package io.whozoss.agentos.sdk.api.configChange

import io.swagger.v3.oas.annotations.media.Schema

data class ConfigSecretInputDto(
    @field:Schema(description = "Key to send in ConfigChangeApplyRequest.secrets: '<ref>.<path>'.")
    val key: String,
    val path: String,
    val label: String,
)
