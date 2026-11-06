package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema

/** What the instance offers for messaging. */
data class MessagingCapabilitiesDto(
    @field:Schema(
        description =
            "The platforms where the bot of an agent can be created from AgentOS, with no token to copy: " +
                "those with a running bot manager",
    )
    val botCreation: List<MessagingPlatform>,
)
