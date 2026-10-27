package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode

data class OperationInput(
    val ref: String? = null,
    val kind: String? = null,
    val action: String? = null,
    val id: String? = null,
    val scope: ScopeInput? = null,
    val value: JsonNode? = null,
    val changes: List<ChangeInput>? = null,
    val secrets: List<SecretInput>? = null,
    val deployTo: String? = null,
)
