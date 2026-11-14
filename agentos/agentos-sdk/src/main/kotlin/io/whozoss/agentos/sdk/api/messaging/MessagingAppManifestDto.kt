package io.whozoss.agentos.sdk.api.messaging

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The manifest of the app to create for an agent on a platform where a bot is an app the admin creates
 * (Slack): name, permissions and settings AgentOS needs, ready to paste.
 */
data class MessagingAppManifestDto(
    val platform: MessagingPlatform,
    @field:Schema(description = "The manifest, as JSON, to paste where the platform creates an app from a manifest")
    val manifest: String,
    @field:Schema(description = "Where to paste it")
    val createUrl: String,
)
