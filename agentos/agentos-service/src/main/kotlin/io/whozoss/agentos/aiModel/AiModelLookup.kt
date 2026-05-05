package io.whozoss.agentos.aiModel

import io.whozoss.agentos.reconciliation.ConfigLookup
import io.whozoss.agentos.sdk.aiProvider.AiModel
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ConfigLookup] adapter for [AiModel].
 *
 * Wraps [AiModelRepository.findByTriple] — the `name` parameter matches [AiModel.alias]
 * first, falling back to [AiModel.apiModelName] when alias is null (see T3 for the
 * alias-first convention rationale).
 */
@Component
class AiModelLookup(
    private val repository: AiModelRepository,
) : ConfigLookup<AiModel> {
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiModel? = repository.findByTriple(namespaceId, userId, name)
}
