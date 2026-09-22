package io.whozoss.agentos.git

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigPolicy
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Validates namespace Git settings and queues preparation after either configuration API saves them.
 *
 * Reuses [GitRepositorySettingsFactory] for the same stored shape on reads and saves. Saving
 * additionally validates the remote URL and its current DNS resolution; ordinary reads avoid
 * network validation, and the Git runner checks the destination again before remote work.
 */
@Component
class GitRepositoryConfigPolicy(
    private val settingsFactory: GitRepositorySettingsFactory,
    private val checkoutService: RepositoryCheckoutService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
    private val availability: GitAvailability,
) : IntegrationConfigPolicy {
    override fun supports(integrationType: String): Boolean =
        integrationType.equals(GitRepositoryIntegration.TYPE, ignoreCase = true)

    override fun <T> aroundSave(config: IntegrationConfig, action: () -> T): T {
        val namespaceId = config.namespaceId ?: return action() // Validation reports an invalid scope.
        return WorkspaceLifecycleLocks.withNamespace(namespaceId, action)
    }

    override fun afterSave(config: IntegrationConfig) {
        // Both the dedicated settings screen and generic integration CRUD use this path.
        // Keep a saved association usable if queuing fails; the first workspace also ensures readiness.
        try {
            checkoutProvisioner.requestPreparation(settingsFactory.fromConfig(config, validateRemote = false))
        } catch (e: Exception) {
            logger.error { "Could not queue the checkout of namespace ${config.namespaceId} (${e.javaClass.simpleName})" }
        }
    }

    override fun validate(config: IntegrationConfig) {
        if (!availability.isAvailable()) {
            throw BadRequestException("Git repositories require the GIT plugin, which is not loaded on this instance")
        }
        // Parsing is the validation: the factory raises BadRequestException on anything unusable.
        val settings = settingsFactory.fromConfig(config)
        val checkout = checkoutService.findByNamespaceId(settings.namespaceId) ?: return
        if ((checkout.repositoryUrl != settings.repositoryUrl || checkout.mainBranch != settings.mainBranch) &&
            !checkoutProvisioner.canReplaceFailedCheckout(checkout)) {
            throw ConflictException(
                "This namespace already has a checkout of a different repository or main branch. " +
                    "Changing it requires an explicit migration; other settings can still be changed.",
            )
        }
    }

    companion object : KLogging()
}
