package io.whozoss.agentos.sdk.api.configChange

/**
 * Configuration entities the config agent can read and change. A [NAMESPACE] can only be created or updated.
 * An [AI_MODEL] takes its scope from its [AI_PROVIDER], never from the plan.
 */
enum class ConfigKind {
    AGENT,
    SKILL,
    PROMPT,
    INTEGRATION,
    AUTH_SETTING,
    NAMESPACE,
    AI_PROVIDER,
    AI_MODEL,
}
