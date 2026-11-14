package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Sets the bot manager of the instance on a platform, replacing the previous one. */
data class MessagingBotManagerRequest(
    val platform: MessagingPlatform,
    @field:Schema(
        description =
            "The manager bot's token. For Telegram, the bot must have Bot Management Mode on in BotFather's " +
                "Mini App. Stored encrypted, never returned.",
        writeOnly = true,
    )
    @field:NotBlank
    @field:Size(max = 200)
    val token: String,
) {
    /** The token is a credential: it never appears in a log. */
    override fun toString(): String = "MessagingBotManagerRequest(platform=$platform, token=****)"
}
