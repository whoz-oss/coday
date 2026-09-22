package io.whozoss.agentos.exchange

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.integrationConfig.IntegrationConfig

/** Per-run copies only: the namespace's saved integration is never rewritten. */
object WorkspaceIntegrationConfigs {
    fun resolve(configs: List<IntegrationConfig>, root: ResolvedExchangeRoot?, mayWriteWorkspace: Boolean = true): List<IntegrationConfig> {
        val workspace = root?.workspace ?: return configs
        val path = if (mayWriteWorkspace) root.requireWorkingDirectory().toAbsolutePath().normalize().toString() else null
        return configs.mapNotNull { config ->
            val key = when (config.integrationType) {
                "BASH", "TMUX" -> "workingDirectory"
                "MCP_STDIO" -> "cwd"
                else -> return@mapNotNull config
            }
            val saved = config.parameters
            val parameters = when {
                saved == null || saved.isNull -> JsonNodeFactory.instance.objectNode()
                saved is ObjectNode -> saved.deepCopy()
                else -> return@mapNotNull config
            }
            // An integration explicitly targeting another service/directory can opt out. A stdio
            // MCP server is different: agents do not drive it, it often holds credentials in its
            // environment, and project files in the agent-writable worktree (.npmrc, ...) would run
            // code inside it. It enters the workspace only when explicitly configured to.
            val redirectByDefault = config.integrationType != "MCP_STDIO"
            if (parameters.path("useCaseExchangeDirectory").asBoolean(redirectByDefault)) {
                // These commands can write the workspace. Do not inject it when the invoking
                // user only has a sub-case permission or read-only access to the owner.
                if (!mayWriteWorkspace) return@mapNotNull null
                parameters.put(key, path)
                parameters.put("workspaceId", workspace.id.toString())
                if (config.integrationType == "TMUX") {
                    parameters.put("socketName", "agentos-${workspace.id}")
                }
            }
            config.copy(parameters = parameters)
        }
    }
}
