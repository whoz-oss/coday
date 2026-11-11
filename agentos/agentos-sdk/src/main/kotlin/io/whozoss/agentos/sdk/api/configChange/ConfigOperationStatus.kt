package io.whozoss.agentos.sdk.api.configChange

/** Where one operation stands against the real state, recomputed at every read. */
enum class ConfigOperationStatus {
    PENDING,
    APPLIED,
    ALREADY_APPLIED,
    CONFLICT,
    NOT_VISIBLE,
}
