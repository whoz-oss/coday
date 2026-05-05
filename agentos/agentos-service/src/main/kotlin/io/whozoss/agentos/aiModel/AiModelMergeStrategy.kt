package io.whozoss.agentos.aiModel

import io.whozoss.agentos.reconciliation.MergeStrategy
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.stereotype.Component

/**
 * [MergeStrategy] for [AiModel].
 *
 * Identity fields (`id`, `metadata`, `namespaceId`, `userId`, `aiProviderId`, `alias`) are
 * always preserved from `base`. `alias` is the reconciliation key — changing it would break
 * the triple match that located this override in the first place.
 *
 * Functional fields apply override-wins semantics:
 * - `apiModelName`: override wins when non-null and non-blank.
 * - `temperature`, `maxTokens`, `description`: override wins when non-null.
 * - `priority`: override wins when non-zero. `0` is treated as "unset" because it is the
 *   default DTO value — overriding a namespace model to explicit priority 0 would be a no-op
 *   in practice, and the namespace value is more informative. This semantic is documented in
 *   story 6.4 Dev Notes §"Décision priority".
 */
@Component
class AiModelMergeStrategy : MergeStrategy<AiModel> {
    override fun merge(
        base: AiModel,
        override: AiModel,
    ): AiModel =
        base.copy(
            apiModelName = override.apiModelName.takeIf { it.isNotBlank() } ?: base.apiModelName,
            temperature = override.temperature ?: base.temperature,
            maxTokens = override.maxTokens ?: base.maxTokens,
            priority = if (override.priority != 0) override.priority else base.priority,
            description = override.description ?: base.description,
        )
}
