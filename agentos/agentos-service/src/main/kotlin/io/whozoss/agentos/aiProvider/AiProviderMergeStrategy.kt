package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.reconciliation.MergeStrategy
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.stereotype.Component

/**
 * [MergeStrategy] for [AiProvider].
 *
 * Identity fields (`id`, `metadata`, `namespaceId`, `userId`, `name`) are always preserved
 * from `base` (the lower-precedence layer). Functional fields apply override-wins semantics:
 *
 * - `apiKey`: override wins when non-null and non-blank; otherwise `base.apiKey` is preserved.
 *   This mirrors [AiProviderController]'s `resolveApiKey` 4-way contract — a blank or null override must not
 *   accidentally zero-out the effective API key (would cause a 401 at the provider, story 6.4 AC8).
 * - `baseUrl`: override wins when non-null and non-blank.
 * - `description`: override wins when non-null.
 * - `apiType`: override wins (in practice all layers share the same apiType).
 */
@Component
class AiProviderMergeStrategy : MergeStrategy<AiProvider> {
    override fun merge(
        base: AiProvider,
        override: AiProvider,
    ): AiProvider =
        base.copy(
            apiType = override.apiType,
            baseUrl = override.baseUrl?.takeIf { it.isNotBlank() } ?: base.baseUrl,
            apiKey = override.apiKey?.takeIf { it.isNotBlank() } ?: base.apiKey,
            description = override.description ?: base.description,
        )
}
