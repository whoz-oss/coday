package io.whozoss.agentos.caseFlow

/**
 * Well-known keys for the [io.whozoss.agentos.sdk.caseEvent.MessageEvent.sessionContext] map.
 *
 * The sessionContext is a general-purpose opaque map that travels from the message injection
 * point (HTTP controller or [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor]) all
 * the way to the agent's prompt-building logic. Using string literals at both ends creates an
 * invisible contract that breaks silently on a typo. This object makes the contract explicit
 * and refactor-safe.
 */
object SessionContextKeys {
    /**
     * BCP 47 language tag expressing the user's preferred response language.
     *
     * Set by [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor] from
     * [io.whozoss.agentos.user.User.preferredLanguage] when starting a scheduled case.
     * Read by [io.whozoss.agentos.agent.AgentAdvanced.buildUserFacingGuidelines] as a
     * fallback when no language can be detected from the user's messages.
     */
    const val PREFERRED_LANGUAGE = "preferredLanguage"
}
