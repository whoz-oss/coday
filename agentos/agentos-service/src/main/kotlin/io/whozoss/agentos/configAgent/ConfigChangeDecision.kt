package io.whozoss.agentos.configAgent

enum class ConfigChangeDecision {
    APPLIED,
    PARTIAL,
    FAILED,
    REJECTED,
}
