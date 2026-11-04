package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Attaches a bot created outside AgentOS (with Telegram's BotFather) to an agent. */
data class MessagingBotAttachRequest(
    val platform: MessagingPlatform,
    @field:Schema(description = "The bot's token. Stored encrypted, never returned.", writeOnly = true)
    @field:NotBlank
    @field:Size(max = 200)
    val token: String,
) {
    /** The token is a credential: it never appears in a log. */
    override fun toString(): String = "MessagingBotAttachRequest(platform=$platform, token=****)"
}
