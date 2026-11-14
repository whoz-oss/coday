package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema

/** A messaging application AgentOS can talk through. One schema in the API, shared by every DTO. */
@Schema(enumAsRef = true)
enum class MessagingPlatform {
    TELEGRAM,
    SLACK,
}
