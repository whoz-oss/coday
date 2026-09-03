package io.whozoss.agentos.queryUser

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import mu.KLogging
import org.springframework.stereotype.Service

/**
 * Owns the grant decision for the built-in `queryUser` tool.
 *
 * Mirrors the pattern of
 * [io.whozoss.agentos.exchange.ExchangeToolGrantService]: resolution and granting are
 * two separate calls so callers can check enablement cheaply before paying any
 * further cost.
 *
 * ## Why a separate service?
 *
 * [io.whozoss.agentos.tool.ToolResolverService.resolveToolsForRun] only resolves
 * tools for declared [io.whozoss.agentos.integrationConfig.IntegrationConfig] entries.
 * `queryUser` has no config schema ([QueryUserToolPlugin.configSchema] is `null`) and
 * therefore never appears in the namespace integration catalogue — it cannot be
 * declared. This service provides the parallel grant path that bypasses the
 * integration-config resolver, exactly as
 * [io.whozoss.agentos.exchange.ExchangeToolGrantService] does for file-exchange tools.
 *
 * ## Enablement table
 *
 * The [integrations] map on [io.whozoss.agentos.agentConfig.AgentConfig] drives the
 * decision; [QueryUserConfigProperties.enabledByDefault] is the fallback when the key
 * is absent:
 *
 * | Condition | Result |
 * |---|---|
 * | key absent + `enabledByDefault = true` | granted |
 * | key absent + `enabledByDefault = false` | not granted |
 * | key present, value `null` | granted |
 * | key present, value `[]` (empty list) | **opt-out** — not granted |
 * | key present, value non-empty list | granted (only one tool anyway) |
 *
 * The empty-list opt-out is the escape hatch for fully autonomous agents triggered by
 * webhooks where nobody is listening: an unanswered question would block the case
 * indefinitely.
 *
 * `containsKey` and `get` are both used: a key absent and a key mapped to `null` are
 * indistinguishable through `get` alone, yet they mean opposite things.
 */
@Service
class QueryUserToolGrantService(
    private val properties: QueryUserConfigProperties,
    private val plugin: QueryUserToolPlugin,
) {
    /**
     * Returns `true` when the agent should receive the `queryUser` tool, `false` when
     * it should not.
     *
     * Cheap: only reads the [integrations] map and the bound [properties]. No I/O.
     */
    fun isGranted(integrations: Map<String, List<String>?>?): Boolean {
        val key = QueryUserToolPlugin.INTEGRATION_TYPE
        val declared = integrations?.containsKey(key) == true
        val declaration = integrations?.get(key)
        return when {
            !declared -> properties.enabledByDefault
            declaration == null -> true
            declaration.isEmpty() -> false // explicit opt-out
            else -> true
        }
    }

    /**
     * Builds the `queryUser` tool list (always exactly one tool when granted).
     *
     * [configName] is passed as `null` so the tool is registered under its bare name
     * `"queryUser"` — more readable for the LLM and consistent with the legacy Express
     * backend. If an agent also has a `QUERY_USER` [io.whozoss.agentos.integrationConfig.IntegrationConfig]
     * declared (which creates a prefixed name like `MY_CONF__queryUser`), the caller's
     * `dedupToolsByName` keeps the first occurrence and warns on the collision.
     */
    fun grantTools(toolContext: ToolContext): List<StandardTool<*>> {
        logger.debug { "Granting built-in queryUser tool to agent '${toolContext.agentName}'" }
        return plugin.provideTools(config = null, configName = null, context = toolContext)
    }

    companion object : KLogging()
}
