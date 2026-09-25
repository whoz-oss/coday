package io.whozoss.agentos.git

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
    private val settingsFactory: GitRepositorySettingsFactory,
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
        integrationConfigService
            .findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)
            ?.let { settingsFactory.fromConfig(it, validateRemote = false) }

    /** Disabled automation must never make ordinary conversation creation depend on Git. */
    fun findAutomaticSettings(namespaceId: UUID): GitRepositorySettings? {
        val config = integrationConfigService.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)
            ?: return null
        if (config.parameters?.get(GitRepositoryIntegration.PARAM_AUTO_WORKTREE)?.asBoolean(false) != true) return null
        // The saved shape is parsed here; DNS and transport checks belong to actual Git execution.
        return settingsFactory.fromConfig(config, validateRemote = false)
    }
}
