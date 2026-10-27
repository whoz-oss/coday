package io.whozoss.agentos.configAgent

/** Where one operation stands against the real state, recomputed at every read. */
enum class ConfigOperationStatus {
    PENDING,
    APPLIED,
    ALREADY_APPLIED,
    CONFLICT,
    NOT_VISIBLE,
}
