package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** A messaging account linked to the current user through a bot. */
data class MessagingIdentityDto(
    val id: UUID,
    val platform: MessagingPlatform,
    @field:Schema(description = "The bot the account was linked through; null when that bot was removed since")
    val botId: UUID?,
    @field:Schema(description = "The bot's username, without @; null when the bot was removed since")
    val botUsername: String?,
    @field:Schema(description = "The agent the bot answers as; null when the bot was removed since")
    val agentName: String?,
    @field:Schema(description = "How the platform names the account")
    val displayName: String,
    val linkedAt: Instant,
)
