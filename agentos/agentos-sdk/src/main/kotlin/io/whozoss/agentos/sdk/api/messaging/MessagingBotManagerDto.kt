package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * The bot manager of the instance on a platform: the bot that creates the bots of agents for their admins, so
 * that nobody copies a token. Never carries its token.
 */
data class MessagingBotManagerDto(
    val platform: MessagingPlatform,
    @field:Schema(description = "The manager's username on the platform, without @")
    val username: String,
    val status: MessagingBotStatus,
    val createdAt: Instant,
)
