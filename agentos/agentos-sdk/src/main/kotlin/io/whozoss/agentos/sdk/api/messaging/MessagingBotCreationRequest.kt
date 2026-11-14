package io.whozoss.agentos.sdk.api.messaging

/** Asks for the link that creates a bot for an agent through the instance's bot manager, with no token to copy. */
data class MessagingBotCreationRequest(
    val platform: MessagingPlatform,
)
