package io.whozoss.agentos.git

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigPolicy
import mu.KLogging
import org.springframework.beans.factory.ObjectProvider
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
    private val gitRepoSettingsFactory: GitRepositorySettingsFactory,
    private val checkoutService: RepositoryCheckoutService,
    private val checkoutProvisioner: RepositoryCheckoutProvisioner,
    private val availability: GitAvailability,
    private val worker: ObjectProvider<CaseWorkspaceWorker>,
) : IntegrationConfigPolicy {
    override fun supports(integrationType: String): Boolean =
        integrationType.equals(GitRepositoryIntegration.TYPE, ignoreCase = true)

    override fun afterSave(config: IntegrationConfig) {
        // Both the dedicated settings screen and generic integration CRUD use this path. If queuing the
        // clone fails, the association stays saved: preparing the first case worktree also clones the
        // repository when it is not ready yet.
        try {
            checkoutProvisioner.requestPreparation(gitRepoSettingsFactory.fromConfig(config))
        } catch (e: Exception) {
            logger.error(e) { "Could not queue the checkout of namespace ${config.namespaceId}" }
        }
    }

    override fun validate(config: IntegrationConfig) {
        if (!availability.isAvailable()) {
            throw BadRequestException("Git repositories require the GIT plugin, which is not loaded on this instance")
        }
        // Without the worker nothing would ever prepare the repository: the checkout would stay
        // PREPARING. Refuse rather than record an association this instance cannot serve.
        if (worker.getIfAvailable() == null) {
            throw ConflictException(
                "Git repositories require the Git worker, which is disabled on this instance (AGENTOS_GIT_WORKER_ENABLED)",
            )
        }
        // Parsing is the validation: the factory raises BadRequestException on anything unusable.
        val settings = gitRepoSettingsFactory.fromConfig(config)
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
