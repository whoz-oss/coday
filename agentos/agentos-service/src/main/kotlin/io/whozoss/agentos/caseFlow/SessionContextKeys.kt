package io.whozoss.agentos.caseFlow

/**
 * Well-known keys for the [io.whozoss.agentos.sdk.caseEvent.MessageEvent.sessionContext] map.
 *
 * The sessionContext is a general-purpose opaque map that travels from the message injection
 * point (HTTP controller, [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor] or
 * [io.whozoss.agentos.casePlugin.CaseStarterService]) all the way to the agent's prompt-building logic.
 * Using string literals at both ends creates an invisible contract that breaks silently on a typo.
 * This object makes the contract explicit and refactor-safe.
 *
 * The map's format is not agentos's: it belongs to whoever writes it (the UI's backend, the
 * `UserContextProvider` plugin, the caller of `FreeCaseStarter`), and agentos only carries it. This object
 * holds the few keys agentos itself writes or reads, through the accessors of `SessionContextAccessors.kt`:
 * [PREFERRED_LANGUAGE], and [FIRST_NAME] and [LAST_NAME] of [USER_CONTEXT]. The rest is read by the LLM, which is
 * shown the whole map of the last user message, and by the tools of plugins, which receive the case events.
 */
object SessionContextKeys {
    /**
     * BCP 47 language tag expressing the user's preferred response language.
     *
     * Set from [io.whozoss.agentos.user.User.preferredLanguage] when starting a case on behalf of a user
     * ([io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor], [io.whozoss.agentos.casePlugin.CaseStarterService]).
     * Read by [io.whozoss.agentos.agent.AgentAdvanced.buildUserFacingGuidelines] as a
     * fallback when no language can be detected from the user's messages.
     */
    const val PREFERRED_LANGUAGE = "preferredLanguage"

    /**
     * Identity of the user the message is for, a map produced by the `UserContextProvider` plugin (or by the
     * UI's backend for a user message). Agentos reads only [FIRST_NAME] and [LAST_NAME] of it, to name the user
     * in the final response of an advanced agent.
     */
    const val USER_CONTEXT = "userContext"

    /** First name, in [USER_CONTEXT]. */
    const val FIRST_NAME = "firstName"

    /** Last name, in [USER_CONTEXT]. */
    const val LAST_NAME = "lastName"
}
