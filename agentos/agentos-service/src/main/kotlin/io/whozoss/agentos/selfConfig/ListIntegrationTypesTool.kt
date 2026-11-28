package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Lists the integration types this AgentOS instance knows how to run, **with their configuration
 * schemas**.
 *
 * The schema is the point of this tool, not a decoration: it is the only way the agent can learn
 * which parameters [CreateIntegrationTool] expects for a given type. Without it the agent would
 * guess parameter names, and a wrong guess is only caught much later, when the configuration is
 * resolved into a tool at run time.
 *
 * Read-only, so no confirmation gate.
 *
 * @param configName The [io.whozoss.agentos.integrationConfig.IntegrationConfig.name] used as
 *   tool-name prefix (e.g. `SELF_CONFIG__listIntegrationTypes`). Null for the bare name.
 * @param typesLoader Supplies the catalogue; wired in [SelfConfigToolPluginConfiguration].
 */
class ListIntegrationTypesTool(
    configName: String? = null,
    private val typesLoader: () -> List<IntegrationTypeDescriptor>,
) : StandardTool<ListIntegrationTypesTool.Input> {
    /**
     * @param type Optional exact type identifier (case-insensitive). Supply it once the agent
     *   knows which type it wants: the full catalogue carries every configuration schema and is
     *   therefore long.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val type: String? = null,
    )

    override val name: String = configName?.let { "${it}__listIntegrationTypes" } ?: "listIntegrationTypes"

    override val description: String =
        """
        List the integration types available on this AgentOS instance, each with the JSON Schema of
        the parameters it expects.

        Call this BEFORE creating an integration configuration: the schema tells you exactly which
        parameter names and value types that integration requires, so you do not have to guess.

        Pass 'type' to retrieve a single type once you know which one you need — the full catalogue
        is long because it includes every schema.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "type": {
              "type": "string",
              "description": "Optional exact integration type identifier (e.g. 'MCP_HTTP', 'DATETIME'). Omit to list all types."
            }
          },
          "additionalProperties": false
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override fun outputSchema(): JsonNode = TYPE_ARRAY_SCHEMA

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val filter = input?.type?.trim()?.takeIf { it.isNotBlank() }
        val all = typesLoader()
        val types = filter?.let { f -> all.filter { it.type.equals(f, ignoreCase = true) } } ?: all

        if (filter != null && types.isEmpty()) {
            return ToolExecutionResult.error(
                "No integration type named '$filter'. Known types: ${all.joinToString(", ") { it.type }}.",
                errorType = SelfConfigErrors.NOT_FOUND,
            )
        }

        val structured =
            SelfConfigRendering.mapper.createArrayNode().apply {
                types.forEach { descriptor ->
                    addObject().apply {
                        put("type", descriptor.type)
                        put("displayName", descriptor.displayName)
                        put("description", descriptor.description)
                        set<JsonNode>("configSchema", descriptor.configSchema ?: nullNode())
                    }
                }
            }
        val summary =
            if (types.isEmpty()) {
                "No integration type is available on this instance."
            } else {
                "${types.size} integration type(s) available: ${types.joinToString(", ") { it.type }}."
            }
        return ToolExecutionResult.success(output = summary, structuredOutput = structured)
    }

    companion object {
        val TYPE_ARRAY_SCHEMA: JsonNode =
            SelfConfigRendering.mapper.readTree(
                """
                {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "type":         { "type": "string" },
                      "displayName":  { "type": "string" },
                      "description":  { "type": "string" },
                      "configSchema": { "type": ["object", "null"] }
                    },
                    "required": ["type", "displayName"]
                  }
                }
                """.trimIndent(),
            )
    }
}
