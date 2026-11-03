package io.whozoss.agentos.sdk.api.configChange

/** Configuration entities the config agent can read and change. A [NAMESPACE] can only be created. */
enum class ConfigKind {
    AGENT,
    SKILL,
    PROMPT,
    INTEGRATION,
    AUTH_SETTING,
    NAMESPACE,
}
