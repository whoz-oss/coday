package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.reconciliation.ConfigLookup
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ConfigLookup] adapter for [AiProvider].
 *
 * Trivial wrapper around [AiProviderRepository.findByTriple] — kept as a separate bean
 * so the generic [io.whozoss.agentos.reconciliation.ConfigMergeService] can be wired
 * by [io.whozoss.agentos.reconciliation.MergeConfiguration] without leaking the
 * repository contract into the reconciliation package.
 */
@Component
class AiProviderLookup(
    private val repository: AiProviderRepository,
) : ConfigLookup<AiProvider> {
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiProvider? = repository.findByTriple(namespaceId, userId, name)
}
