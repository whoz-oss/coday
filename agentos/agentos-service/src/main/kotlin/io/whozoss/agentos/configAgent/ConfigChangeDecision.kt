package io.whozoss.agentos.configAgent

/** [FAILED]: nothing was written. [PARTIAL]: something was, an entity created but not deployed included. */
enum class ConfigChangeDecision {
    APPLIED,
    PARTIAL,
    FAILED,
    REJECTED,
}
