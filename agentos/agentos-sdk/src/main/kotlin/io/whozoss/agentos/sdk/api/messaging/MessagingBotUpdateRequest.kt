package io.whozoss.agentos.sdk.api.messaging

/** Turns a bot on or off. Off, the bot receives nothing and its users' links are kept. */
data class MessagingBotUpdateRequest(
    val enabled: Boolean,
)
