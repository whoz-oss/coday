package io.whozoss.agentos.sdk.auth

sealed class AccessDecision {
    data class Granted(val effectiveRole: String) : AccessDecision()
    data class Denied(
        val reason: String,
        val usersWithAccess: List<String> = emptyList(),
    ) : AccessDecision()
    data object Abstain : AccessDecision()

    val isGranted: Boolean get() = this is Granted
}
