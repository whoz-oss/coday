package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.integrationConfig.IntegrationConfig

/** Per-run copies only: the namespace's saved integration is never rewritten. */
object WorkspaceIntegrationConfigs {
    fun resolve(configs: List<IntegrationConfig>, root: ResolvedExchangeRoot?): List<IntegrationConfig> {
        val binding = root?.binding ?: return configs
        val path = root.requireRepository().toAbsolutePath().normalize().toString()
        return configs.map { config ->
            val key = when (config.integrationType) {
                "BASH", "TMUX" -> "workingDirectory"
                "MCP_STDIO" -> "cwd"
                else -> return@map config
            }
            val saved = config.parameters
            val parameters = when {
                saved == null || saved.isNull -> JsonNodeFactory.instance.objectNode()
                saved is ObjectNode -> saved.deepCopy()
                else -> return@map config
            }
            // An integration explicitly targeting another service/directory can opt out.
            if (parameters.path("useCaseExchangeDirectory").asBoolean(true)) {
                parameters.put(key, path)
                parameters.put("workspaceId", binding.rootCaseId.toString())
                if (config.integrationType == "TMUX") {
                    parameters.put("socketName", "agentos-${binding.rootCaseId}")
                }
            }
            config.copy(parameters = parameters)
        }
    }
}
