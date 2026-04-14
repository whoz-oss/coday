package io.whozoss.agentos.sdk.auth

enum class ToolCategory(val riskLabel: String, val minimumNamespaceRole: NamespaceRole) {
    READ_ONLY("low risk", NamespaceRole.VIEWER),
    WRITE("medium risk", NamespaceRole.MEMBER),
    DESTRUCTIVE("high risk", NamespaceRole.ADMIN),
    ADMIN("critical", NamespaceRole.ADMIN),
}
