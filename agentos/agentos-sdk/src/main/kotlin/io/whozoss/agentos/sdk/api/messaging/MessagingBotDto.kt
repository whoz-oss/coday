package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** The bot of an agent on a messaging platform. Never carries the bot's token. */
data class MessagingBotDto(
    val id: UUID,
    val platform: MessagingPlatform,
    val agentId: UUID,
    val agentName: String,
    @field:Schema(description = "The namespace the agent answers in: the cases its bot starts belong to it")
    val namespaceId: UUID,
    @field:Schema(description = "The bot's username on the platform, without @")
    val username: String,
    @field:Schema(description = "How the platform names the bot")
    val displayName: String,
    val enabled: Boolean,
    @field:Schema(description = "True when AgentOS created the bot through the instance's bot manager")
    val managed: Boolean,
    val status: MessagingBotStatus,
    @field:Schema(description = "The address that opens the bot in the messaging application")
    val url: String,
    @field:Schema(description = "True when the current user has linked an account through this bot")
    val linked: Boolean,
    val createdAt: Instant,
)
