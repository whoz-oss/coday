package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Lists the **calling user's own** integration configurations, parameters included.
 *
 * Returning parameters in clear text is deliberate and is exactly why this integration is confined
 * to user-owned rows: they are the user's own values, shown back to the user's own agent. The
 * moment that boundary moved, this output would become a credential leak -- see the
 * "User-owned overlays only" section of [SelfConfigToolService].
 *
 * It is also the mandatory first step of an update: `parameters` are replaced wholesale, so the
 * agent must read the current object before sending a modified one.
 *
 * Read-only, so no confirmation gate.
 */
class ListIntegrationsTool(
    configName: String? = null,
    private val listLoader: (ToolContext) -> SelfConfigOutcome<List<IntegrationConfig>>,
) : StandardTool<ListIntegrationsTool.Input> {
    /**
     * No input: the scope is the calling user, which the run already determines.
     *
     * `ignoreUnknown` because an LLM handed a parameterless schema still occasionally invents a
     * filter argument, and a deserialization failure there would surface as an opaque tool error
     * rather than the list it asked for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    class Input

    override val name: String = configName?.let { "${it}__listIntegrations" } ?: "listIntegrations"

    override val description: String =
        """
        List the user's own personal integration configurations, with their current parameters.

        These are the configurations the user owns: either 'global' (they apply to the user in every
        namespace) or 'namespace' (they apply to the user in one namespace only). Shared
        configurations defined by an administrator are NOT listed here and cannot be modified from
        this conversation.

        Always call this before updating a configuration: parameters are replaced as a whole, so you
        need the current object to send back a complete, modified one.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {},
          "additionalProperties": false
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override fun outputSchema(): JsonNode = SelfConfigRendering.CONFIG_ARRAY_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult =
        when (val outcome = listLoader(context)) {
            is SelfConfigOutcome.Failure -> SelfConfigRendering.toError(outcome)
            is SelfConfigOutcome.Ok -> {
                val configs = outcome.value
                val structured =
                    SelfConfigRendering.mapper.createArrayNode().apply {
                        configs.forEach { add(SelfConfigRendering.toNode(it)) }
                    }
                val summary =
                    if (configs.isEmpty()) {
                        "The user has no personal integration configuration yet."
                    } else {
                        "${configs.size} personal integration configuration(s): " +
                            configs.joinToString("; ") { SelfConfigRendering.summarize(it) }
                    }
                ToolExecutionResult.success(output = summary, structuredOutput = structured)
            }
        }
}
