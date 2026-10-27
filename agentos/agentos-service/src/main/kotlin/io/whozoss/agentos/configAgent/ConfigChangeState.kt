package io.whozoss.agentos.configAgent

/** Lifecycle of a proposal, derived from the case events and the clock; never stored. */
enum class ConfigChangeState {
    OPEN,
    IN_PROGRESS,
    ANSWERED,
    EXPIRED,
    SUPERSEDED,
}
