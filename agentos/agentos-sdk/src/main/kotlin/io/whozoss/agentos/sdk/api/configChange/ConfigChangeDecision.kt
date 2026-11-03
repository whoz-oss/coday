package io.whozoss.agentos.sdk.api.configChange

/** [FAILED]: nothing was written. [PARTIAL]: something was, an entity created but not deployed included. */
enum class ConfigChangeDecision {
    APPLIED,
    PARTIAL,
    FAILED,
    REJECTED,
}
