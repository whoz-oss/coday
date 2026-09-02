package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Request body for `POST /api/prompts/{id}/translations/{languageCode}`.
 *
 * Both fields are optional for namespace-scoped prompts — the namespace is inferred
 * from the prompt itself. For platform-scoped prompts (namespaceId IS NULL on the
 * prompt), at least one of [namespaceId] / [namespaceExternalId] must be provided
 * so the endpoint can resolve the AI model to use for translation.
 */
@Schema(name = "PromptTranslateRequest")
data class PromptTranslateRequest(
    @field:Schema(
        description = "Namespace UUID for AI model resolution. " +
            "Required only for platform-scoped prompts. " +
            "Mutually exclusive with namespaceExternalId.",
        nullable = true,
        format = "uuid",
    )
    val namespaceId: UUID? = null,
    @field:Schema(
        description = "Namespace external ID for AI model resolution (resolved server-side). " +
            "Required only for platform-scoped prompts. " +
            "Mutually exclusive with namespaceId.",
        nullable = true,
    )
    val namespaceExternalId: String? = null,
)
