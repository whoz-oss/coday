package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Request body for `POST /api/prompts/{id}/translations/{languageCode}`.
 *
 * Exactly one of [namespaceId] / [namespaceExternalId] must be provided so the
 * endpoint can resolve the AI model to use for translation.
 * Platform-level prompts (namespaceId IS NULL on the prompt itself) still require
 * a namespace context here — the caller knows which namespace the user is in.
 */
@Schema(name = "PromptTranslateRequest")
data class PromptTranslateRequest(
    @field:Schema(
        description = "Namespace UUID for AI model resolution. " +
            "Mutually exclusive with namespaceExternalId; at least one is required.",
        nullable = true,
        format = "uuid",
    )
    val namespaceId: UUID? = null,
    @field:Schema(
        description = "Namespace external ID for AI model resolution (resolved server-side). " +
            "Mutually exclusive with namespaceId; at least one is required.",
        nullable = true,
    )
    val namespaceExternalId: String? = null,
)
