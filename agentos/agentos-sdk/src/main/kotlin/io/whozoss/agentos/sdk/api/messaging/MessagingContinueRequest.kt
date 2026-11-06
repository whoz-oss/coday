package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class MessagingContinueRequest(
    @field:Schema(description = "The bot whose direct chat with the current user continues the case")
    val botId: UUID,
)
