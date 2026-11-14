package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema

/** Whether a bot is receiving messages, and why not. */
@Schema(enumAsRef = true)
enum class MessagingBotStatus {
    /** Connecting to the platform. */
    STARTING,

    /** Receiving messages. */
    RUNNING,

    /** Turned off in AgentOS: the bot receives nothing. */
    DISABLED,

    /** The platform refuses the bot's token: it was revoked or replaced. The bot stays off until attached again. */
    TOKEN_REJECTED,

    /** Another program receives this bot's messages (another AgentOS instance with the same bot): retrying. */
    CONFLICT,

    /** The platform cannot be reached: retrying. */
    UNREACHABLE,
}
