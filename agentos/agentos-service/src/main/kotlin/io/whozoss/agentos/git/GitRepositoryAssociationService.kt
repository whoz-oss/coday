package io.whozoss.agentos.git

import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import mu.KLogging
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
     * The namespace's validated Git association, or null when it has none.
     *
     * A row that exists but does not validate raises rather than returning null. Silently
     * treating a broken association as "no Git" would hide a configuration error and defer the failure until the background clone.
     *
     * @throws io.whozoss.agentos.exception.BadRequestException when the stored row is not a usable
     *   association.
     */
    fun findSettings(namespaceId: UUID): GitRepositorySettings? =
        integrationConfigService
            .findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)
            ?.let { settingsFactory.fromConfig(it) }

    /** Whether the namespace has a repository associated at all. */
    fun isAssociated(namespaceId: UUID): Boolean = findSettings(namespaceId) != null

    companion object : KLogging()
}
