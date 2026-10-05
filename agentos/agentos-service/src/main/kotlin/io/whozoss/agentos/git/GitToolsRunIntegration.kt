package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitToolParameters
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Binds the `GIT` tool integration to the family's worktree for each run of an equipped family.
 *
 * There the tools only ever work in the worktree, with the Git context recorded when the family was
 * equipped. The administrative directory is pinned by name, so they never trust the worktree's own
 * `.git` pointer file, which an agent can rewrite, and saved values never override this context. A
 * user who cannot write to the workspace gets no Git tool. Outside an equipped family, a `GIT`
 * integration is an ordinary one and reaches the run as configured, as it does on an instance
 * without `agentos.git.workspaces.enabled`, where this customizer is not installed.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitToolsRunIntegration(
    private val resolver: GitExchangeRootResolver,
    private val exchangeCapabilityService: ExchangeCapabilityService,
    private val exchangeStorageService: ExchangeStorageService,
) : RunIntegrationCustomizer {
    override fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig> {
        if (configs.none { it.integrationType == GitAvailability.TOOLS_INTEGRATION_TYPE }) return configs
        val caseId = context.caseId ?: return configs
        val root = resolver.resolveGit(caseId)
        if (root.binding == null) return configs
        val parameters = toolParameters(root, caseId, context.userId)
        return configs.mapNotNull { config ->
            if (config.integrationType != GitAvailability.TOOLS_INTEGRATION_TYPE) config
            else parameters?.let { withParameters(config, it) }
        }
    }

    private fun toolParameters(
        root: GitExchangeRoot,
        caseId: UUID,
        userId: UUID?,
    ): Map<String, String>? {
        val binding = root.binding ?: return null
        val settings = binding.settings ?: return null
        if (!exchangeCapabilityService.canAccessCase(userId?.toString(), caseId, root.exchange, Action.WRITE)) return null
        root.requireUsable()
        val common = exchangeStorageService.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()
        return mapOf(
            GitToolParameters.WORKING_DIRECTORY to root.repositoryPath.toAbsolutePath().normalize().toString(),
            GitToolParameters.GIT_DIR to common.worktreeRegistration(binding.rootCaseId).toString(),
            GitToolParameters.COMMON_GIT_DIR to common.toString(),
            GitToolParameters.REPOSITORY_URL to settings.repositoryUrl,
            GitToolParameters.MAIN_BRANCH to settings.mainBranch,
        )
    }

    private fun withParameters(
        config: IntegrationConfig,
        values: Map<String, String>,
    ): IntegrationConfig? {
        val parameters =
            when (val saved = config.parameters) {
                null -> JsonNodeFactory.instance.objectNode()
                is ObjectNode -> saved.deepCopy()
                else -> if (saved.isNull) JsonNodeFactory.instance.objectNode() else return null
            }
        values.forEach { (key, value) -> parameters.put(key, value) }
        return config.copy(parameters = parameters)
    }
}
