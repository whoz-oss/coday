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
 * [PREFERRED_LANGUAGE], [FIRST_NAME] and [LAST_NAME] of [USER_CONTEXT], and [MESSAGING]. The rest is read by
 * the LLM, which is shown the whole map of the last user message, and by the tools of plugins, which receive
 * the case events.
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

    /**
     * Where a message sent from a messaging application came from, a map written by
     * [io.whozoss.agentos.messaging.MessagingGateway]: [PLATFORM] tells the agent which application the user
     * writes from, [CONVERSATION_ID] tells AgentOS where to send the reply. Absent on a message typed in the UI.
     */
    const val MESSAGING = "messaging"

    /** A [io.whozoss.agentos.sdk.api.messaging.MessagingPlatform] name, in [MESSAGING]. */
    const val PLATFORM = "platform"

    /** Id of the [io.whozoss.agentos.messaging.MessagingConversation] the message came from, in [MESSAGING]. */
    const val CONVERSATION_ID = "conversationId"
}
