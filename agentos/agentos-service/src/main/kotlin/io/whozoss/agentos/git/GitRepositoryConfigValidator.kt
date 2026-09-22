package io.whozoss.agentos.git

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigValidator
import org.springframework.stereotype.Component

/**
 * Validates a `GIT_REPOSITORY` configuration at the API edge.
 *
 * Reuses [GitRepositorySettingsFactory], so the rules that govern *reading* an association are
 * exactly the rules that govern *saving* one — the two cannot drift, and a configuration that
 * saves is one the provisioner can actually use.
 */
@Component
class GitRepositoryConfigValidator(
    private val settingsFactory: GitRepositorySettingsFactory,
    private val checkoutService: RepositoryCheckoutService,
) : IntegrationConfigValidator {
    override fun supports(integrationType: String): Boolean =
        integrationType.equals(GitRepositoryIntegration.TYPE, ignoreCase = true)

    override fun validate(config: IntegrationConfig) {
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
}
