package io.whozoss.agentos.tool

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Business logic for the tool-invoke debug endpoint.
 *
 * Resolves a named [io.whozoss.agentos.sdk.tool.StandardTool] from the live
 * integration-config overlay and executes it with a caller-supplied JSON payload.
 *
 * Resolution follows the same four-layer precedence as a regular agent run:
 * 1. [IntegrationConfigService.findEffective] merges platform → namespace-shared →
 *    user-global → user×namespace configs for the given `(namespaceId, userId)` pair.
 * 2. [ToolResolverService.resolveToolsForRun] instantiates tools from every matching
 *    integration config, granting all tools (no agent-level filter).
 * 3. The first tool whose name equals [toolName] is selected.
 *
 * The [ToolContext] is intentionally minimal: no case events, no credential provider,
 * no agent name. Tools that require those (e.g. OAuth-gated MCP tools) will not work
 * correctly here — this is expected for a raw debug invocation.
 */
@Service
class ToolInvokeService(
    private val integrationConfigService: IntegrationConfigService,
    private val toolResolverService: ToolResolverService,
    private val userService: UserService,
) {
    /**
     * Resolve [toolName] from the effective integration configs for
     * `(namespaceId, userId)` and execute it with [payloadJson].
     *
     * @param namespaceId Namespace used to resolve effective integration configs.
     * @param userId Optional user id; when provided, user-scoped overlay layers are included.
     * @param toolName Exact name of the tool (e.g. `"MY_FILES__listFiles"`).
     * @param payloadJson Raw JSON string passed verbatim to
     *   [io.whozoss.agentos.sdk.tool.StandardTool.executeWithJson]. Null for tools
     *   that take no input.
     * @return The [ToolExecutionResult] produced by the tool.
     * @throws ResourceNotFoundException when no tool matching [toolName] is found,
     *   with the list of available tool names included in the message.
     */
    suspend fun invoke(
        namespaceId: UUID,
        userId: UUID?,
        toolName: String,
        payloadJson: String?,
    ): ToolExecutionResult {
        logger.info { "[ToolInvoke] Resolving tool '$toolName' in namespace $namespaceId" }

        val effectiveConfigs = integrationConfigService.findEffective(
            namespaceId = namespaceId,
            userId = userId,
        )

        // Grant all integrations (null value = all tools allowed) so every plugin
        // instantiates its full tool set — the caller picks the one they want by name.
        val agentIntegrations: Map<String, List<String>?> =
            effectiveConfigs.associate { it.name to null }

        val context = ToolContext(
            namespaceId = namespaceId,
            userId = userId,
            userExternalId = userId?.let {
                runCatching { userService.findById(it)?.externalId }.getOrNull()
            },
            caseEvents = emptyList(),
            agentName = null,
        )

        val tools = toolResolverService.resolveToolsForRun(
            agentIntegrations = agentIntegrations,
            context = context,
            allIntegrationConfigs = effectiveConfigs,
        )

        val tool = tools.firstOrNull { it.name == toolName }
            ?: run {
                val available = tools.map { it.name }.sorted()
                logger.warn { "[ToolInvoke] Tool '$toolName' not found. Available: $available" }
                throw ResourceNotFoundException(
                    "Tool '$toolName' not found in namespace $namespaceId. " +
                        "Available tools: $available",
                )
            }

        // Payload is intentionally not logged at INFO — it may contain credentials or PII.
        // Use TRACE only in environments where log data is appropriately secured.
        logger.trace { "[ToolInvoke] Executing tool '${tool.name}' with payload: $payloadJson" }
        logger.info { "[ToolInvoke] Executing tool '${tool.name}'" }
        val result = tool.executeWithJson(payloadJson, context)
        logger.info { "[ToolInvoke] Tool '${tool.name}' finished (success=${result.success})" }

        return result
    }

    companion object : KLogging()
}
