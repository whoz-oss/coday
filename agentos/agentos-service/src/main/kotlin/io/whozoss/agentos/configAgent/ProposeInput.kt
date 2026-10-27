package io.whozoss.agentos.configAgent

/** What the LLM sends to `AGENTOS_CONFIG__propose`. Every field is optional so a malformed call gets a precise error. */
data class ProposeInput(
    val summary: String? = null,
    val operations: List<OperationInput>? = null,
)
