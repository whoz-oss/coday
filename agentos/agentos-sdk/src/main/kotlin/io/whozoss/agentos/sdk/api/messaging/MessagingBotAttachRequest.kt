package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Attaches a bot created outside AgentOS to an agent: a Telegram bot created with BotFather, or a Slack app
 * created from the manifest AgentOS gives ([MessagingBotApi.getMessagingBotAppManifest]).
 */
data class MessagingBotAttachRequest(
    val platform: MessagingPlatform,
    @field:Schema(
        description = "The bot's token: Telegram's from BotFather, Slack's Bot User OAuth Token (xoxb-). Stored encrypted, never returned.",
        writeOnly = true,
    )
    @field:NotBlank
    @field:Size(max = 300)
    val token: String,
    @field:Schema(
        description =
            "Slack only, required there: the app-level token (xapp-) with the connections:write scope, which " +
                "receives the messages (Socket Mode). Stored encrypted, never returned.",
        writeOnly = true,
    )
    @field:Size(max = 300)
    val appToken: String? = null,
) {
    /** The tokens are credentials: they never appear in a log. */
    override fun toString(): String = "MessagingBotAttachRequest(platform=$platform, token=****, appToken=${appToken?.let { "****" }})"
}
