package io.whozoss.agentos.configAgent

import tools.jackson.databind.JsonNode

data class ChangeInput(
    val path: String? = null,
    val value: JsonNode? = null,
    val remove: Boolean? = null,
)
