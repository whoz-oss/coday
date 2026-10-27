package io.whozoss.agentos.configAgent

/** Who a change reaches, shown on the card. Information only: nothing is restricted by it. */
enum class ConfigAffects {
    CALLER_ONLY,
    NAMESPACE_MEMBERS,
    PLATFORM,
}
