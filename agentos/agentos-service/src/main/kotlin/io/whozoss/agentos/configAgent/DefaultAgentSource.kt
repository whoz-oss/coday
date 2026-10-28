package io.whozoss.agentos.configAgent

/** Where a namespace's default agent name comes from. */
enum class DefaultAgentSource {
    /** The namespace's own `defaultAgentName`. */
    NAMESPACE,

    /** `agentos.defaults.agent-name`, used when the namespace names none. */
    PLATFORM,
}
