package io.whozoss.agentos.configAgent

/** Configuration entities the config agent can read and change. */
enum class ConfigKind {
    AGENT,
    SKILL,
    PROMPT,
    INTEGRATION,
    AUTH_SETTING,
}
