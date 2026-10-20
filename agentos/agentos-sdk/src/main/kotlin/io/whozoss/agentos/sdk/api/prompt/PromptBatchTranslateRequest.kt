package io.whozoss.agentos.sdk.api.prompt

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import java.util.UUID

/**
 * Request body for `POST /api/prompts/translations/{languageCode}` (batch translate).
 *
 * [ids] is the list of prompt IDs to translate. The response list preserves the
 * same order.
 *
 * [namespaceId] / [namespaceExternalId] are optional for namespace-scoped prompts
 * (the namespace is inferred from each prompt itself). For platform-scoped prompts
 * (namespaceId IS NULL on the prompt), at least one of the two fields must be
 * provided so the endpoint can resolve the AI model to use for translation.
 * Both fields are mutually exclusive — providing both results in a 400.
 *
 * When a mix of namespace-scoped and platform-scoped prompts is included in [ids],
 * the caller-supplied namespace is used only for the platform-scoped ones.
 */
@Schema(name = "PromptBatchTranslateRequest")
data class PromptBatchTranslateRequest(
    @field:Schema(
        description = "IDs of the prompts to translate. Order is preserved in the response.",
        minLength = 1,
    )
    @field:NotEmpty
    val ids: List<UUID>,

    @field:Schema(
        description = "Namespace UUID for AI model resolution. " +
            "Required only when the batch contains platform-scoped prompts. " +
            "Mutually exclusive with namespaceExternalId.",
        nullable = true,
        format = "uuid",
    )
    val namespaceId: UUID? = null,

    @field:Schema(
        description = "Namespace external ID for AI model resolution (resolved server-side). " +
            "Required only when the batch contains platform-scoped prompts. " +
            "Mutually exclusive with namespaceId.",
        nullable = true,
    )
    val namespaceExternalId: String? = null,
)
