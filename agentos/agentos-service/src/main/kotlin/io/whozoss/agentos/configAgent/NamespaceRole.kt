package io.whozoss.agentos.configAgent

/** The user's role in a namespace, as `AGENTOS_CONFIG__read` reports it. */
enum class NamespaceRole {
    /** Namespace WRITE: administers it (every super-admin does). */
    ADMIN,

    /** Namespace READ only. */
    MEMBER,

    NONE,
}
