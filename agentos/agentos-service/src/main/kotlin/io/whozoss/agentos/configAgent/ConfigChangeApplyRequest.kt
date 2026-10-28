package io.whozoss.agentos.configAgent

import io.swagger.v3.oas.annotations.media.Schema

data class ConfigChangeApplyRequest(
    @field:Schema(description = "planHash of the view the user approved; the server refuses to apply anything else.")
    val expectedPlanHash: String,
    @field:Schema(description = "Secret values typed by the user, keyed '<ref>.<path>'.")
    val secrets: Map<String, String> = emptyMap(),
)
