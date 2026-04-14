package io.whozoss.agentos.sdk.auth

data class ToolRestriction(
    val mode: ToolRestrictionMode,
    val tools: Set<String>,
)

enum class ToolRestrictionMode {
    WHITELIST,
    BLACKLIST,
    NONE,
}
