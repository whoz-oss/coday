package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.reconciliation.ConfigLookup
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ConfigLookup] adapter for [IntegrationConfig].
 *
 * Trivial wrapper around [IntegrationConfigRepository.findByTriple] — kept as a separate bean
 * so the generic [io.whozoss.agentos.reconciliation.ConfigMergeService] can be wired
 * by [io.whozoss.agentos.reconciliation.MergeConfiguration] without leaking the
 * repository contract into the reconciliation package.
 */
@Component
class IntegrationConfigLookup(
    private val repository: IntegrationConfigRepository,
) : ConfigLookup<IntegrationConfig> {
    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? = repository.findByTriple(namespaceId, userId, name)
}
