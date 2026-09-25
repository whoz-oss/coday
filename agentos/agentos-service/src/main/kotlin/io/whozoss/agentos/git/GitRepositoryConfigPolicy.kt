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
 * Reuses [GitRepositorySettingsFactory], so the rules that govern *reading* an association are
 * exactly the rules that govern *saving* one — the two cannot drift, and a configuration that
 * saves is one the provisioner can actually use.
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

    override fun afterSave(config: IntegrationConfig) {
        // Both the dedicated settings screen and generic integration CRUD use this path.
        // Keep a saved association usable if queuing fails; the first workspace also ensures readiness.
        try {
            checkoutProvisioner.requestPreparation(settingsFactory.fromConfig(config))
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
        if (checkout.repositoryUrl != settings.repositoryUrl || checkout.mainBranch != settings.mainBranch) {
            throw ConflictException(
                "This namespace already has a checkout of a different repository or main branch. " +
                    "Changing it requires an explicit migration; other settings can still be changed.",
            )
        }
    }

    companion object : KLogging()
}
