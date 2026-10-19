package io.whozoss.agentos.freeCaseLauncherPlugin

/**
 * The context of the user a case was to be started for could not be resolved: the `UserContextProvider`
 * failed, for good or for now. A failure, not a refusal: it is thrown before anything is written. [message]
 * carries none of the provider's own reason, which may hold the body of an upstream error and is logged instead.
 */
class UserContextUnavailableException(
    message: String,
) : RuntimeException(message)
