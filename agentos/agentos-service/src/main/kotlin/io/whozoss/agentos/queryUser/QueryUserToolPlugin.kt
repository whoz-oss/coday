package io.whozoss.agentos.queryUser

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.stereotype.Component

/**
 * Spring-managed [ToolPlugin] that provides the QUERY_USER integration.
 *
 * Unlike PF4J plugins, this class is annotated with `@Component` directly — no
 * separate `@Configuration` class is needed because [QueryUserToolPlugin] has no
 * Spring dependencies to inject and therefore creates no circular-dependency risk.
 *
 * Compare with [io.whozoss.agentos.redirect.RedirectToolPlugin], which requires a
 * `@Configuration` class ([io.whozoss.agentos.redirect.RedirectConfiguration]) to
 * inject an [io.whozoss.agentos.agentConfig.AgentConfigService]-backed lambda without
 * creating a cycle through [io.whozoss.agentos.agent.AgentServiceImpl]. Here the tool
 * is pure Kotlin with no external state, so `@Component` is both simpler and correct.
 *
 * [io.whozoss.agentos.tool.ToolRegistryService] collects all `ToolPlugin` beans via
 * its `springToolPlugins: List<ToolPlugin>` constructor parameter, so this bean is
 * automatically discovered alongside PF4J-loaded plugins.
 *
 * The integration has no configuration parameters — an agent that declares
 * `QUERY_USER` in its integrations map gets the tool unconditionally. The config schema
 * is therefore `null`, which marks it as a config-less plugin (instantiated once per
 * agent run rather than per namespace integration config).
 */
@Component
class QueryUserToolPlugin : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    /**
     * No configuration parameters needed — the tool derives all its behaviour from the
     * LLM-provided input at call time. A null schema marks this as a config-less plugin:
     * it is instantiated directly per agent run without requiring a persisted
     * [io.whozoss.agentos.integrationConfig.IntegrationConfig].
     */
    override val configSchema: JsonNode? = null

    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> = listOf(QueryUserTool(configName = configName))

    companion object {
        const val INTEGRATION_TYPE = "QUERY_USER"
    }
}
