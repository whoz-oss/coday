package io.whozoss.agentos.sdk.api.configChange

/** Lifecycle of a proposal, derived from the case events and the clock; never stored. */
enum class ConfigChangeState {
    OPEN,
    IN_PROGRESS,
    ANSWERED,
    EXPIRED,
    SUPERSEDED,
}
