package io.whozoss.agentos.sdk.api.case

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Response body of `GET /api/voice-messages/capability`.
 *
 * @property available Whether this instance can transcribe voice messages. Nothing else is exposed:
 *   neither the model nor the provider behind it.
 */
@Schema(name = "VoiceMessageCapabilityResponse")
data class VoiceMessageCapabilityResponse(
    val available: Boolean,
)
