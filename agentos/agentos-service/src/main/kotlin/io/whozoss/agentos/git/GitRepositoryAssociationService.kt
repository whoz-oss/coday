package io.whozoss.agentos.git

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reads the Git association of a namespace.
 *
 * The single entry point every Git-aware code path uses to answer "is this namespace associated
 * with a repository, and how". It deliberately offers no way to ask the question through the
 * layered [IntegrationConfigService.findEffective] resolution.
 */
@Service
class GitRepositoryAssociationService(
    private val integrationConfigService: IntegrationConfigService,
    private val gitRepoSettingsFactory: GitRepositorySettingsFactory,
) {
    /**
     * The namespace's parsed Git association, or null when it has none.
     *
     * Invalid stored fields raise rather than silently disabling Git. DNS and transport checks
     * are repeated by the runner before network operations, without blocking configuration reads.
     *
     * @throws io.whozoss.agentos.exception.BadRequestException when the stored row is not a usable
     *   association.
     */
    fun findSettings(namespaceId: UUID): GitRepositorySettings? =
        activeConfig(namespaceId)?.let { gitRepoSettingsFactory.fromConfig(it, validateRemote = false) }

    /** Whether the namespace equips each new root case. Reads that switch alone, never the other fields. */
    fun automationEnabled(namespaceId: UUID): Boolean =
        activeConfig(namespaceId)?.let { GitRepositoryIntegration.autoWorktree(it.parameters) } ?: false

    /**
     * Disabled automation must never make ordinary conversation creation depend on Git. The saved
     * shape is parsed here. DNS and transport checks belong to actual Git execution.
     */
    fun findAutomaticSettings(namespaceId: UUID): GitRepositorySettings? =
        activeConfig(namespaceId)
            ?.takeIf { GitRepositoryIntegration.autoWorktree(it.parameters) }
            ?.let { gitRepoSettingsFactory.fromConfig(it, validateRemote = false) }

    private fun activeConfig(namespaceId: UUID): IntegrationConfig? =
        integrationConfigService.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)
}
