package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/** A single-use link into a messaging application: to link an account to a bot, or to create a bot. */
data class MessagingLinkDto(
    @field:Schema(description = "Opens the bot in the messaging application; usable once, until expiresAt")
    val url: String,
    @field:Schema(
        description =
            "When set, the message to send to the bot once the link opened the chat: the link of this platform " +
                "cannot carry it (Slack). Usable once, until expiresAt.",
    )
    val code: String? = null,
    val expiresAt: Instant,
)
