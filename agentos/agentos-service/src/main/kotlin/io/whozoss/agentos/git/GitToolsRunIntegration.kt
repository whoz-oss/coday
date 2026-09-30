package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitLayout
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Binds the `GIT` tool integration to the family's worktree for each run.
 *
 * The tools only ever work there, with the Git context recorded when the family was equipped. The
 * administrative directory is pinned by name, so they never trust the worktree's own `.git` pointer
 * file, which an agent can rewrite, and saved values never override this context. Outside a Git
 * workspace, or for a user who cannot write to it, the run gets no Git tool. Installed even without
 * `agentos.git.workspaces.enabled`: a saved `GIT` integration must never reach the plugin with
 * directories of its own choosing.
 */
@Component
class GitToolsRunIntegration(
    /** Absent without Git workspaces: there is then no worktree to bind, and no Git tool. */
    private val resolver: GitExchangeRootResolver?,
    private val exchangeCapabilityService: ExchangeCapabilityService,
    private val exchangeStorageService: ExchangeStorageService,
    private val objectMapper: ObjectMapper,
) : RunIntegrationCustomizer {
    override fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig> {
        if (configs.none { it.integrationType == GitAvailability.TOOLS_INTEGRATION_TYPE }) return configs
        val parameters = context.caseId?.let { toolParameters(it, context.userId) }
        return configs.mapNotNull { config ->
            if (config.integrationType != GitAvailability.TOOLS_INTEGRATION_TYPE) config
            else parameters?.let { withParameters(config, it) }
        }
    }

    private fun toolParameters(
        caseId: UUID,
        userId: UUID?,
    ): Map<String, String>? {
        val root = resolver?.resolveGit(caseId) ?: return null
        val binding = root.binding ?: return null
        val settings =
            binding.settingsJson?.let { objectMapper.readValue(it, GitRepositorySettings::class.java) }
                ?: return null
        if (!exchangeCapabilityService.canAccessCase(userId?.toString(), caseId, root.exchange, Action.WRITE)) return null
        root.requireUsable()
        val common = exchangeStorageService.namespaceGitDirectory(binding.namespaceId).toAbsolutePath().normalize()
        return mapOf(
            "workingDirectory" to root.repositoryPath.toAbsolutePath().normalize().toString(),
            "gitDir" to common.resolve(GitLayout.WORKTREES_DIR).resolve(binding.rootCaseId.toString()).toString(),
            "commonGitDir" to common.toString(),
            "repositoryUrl" to settings.repositoryUrl,
            "mainBranch" to settings.mainBranch,
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
