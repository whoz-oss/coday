package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult

/**
 * Creates a personal integration configuration for the calling user.
 *
 * ## What the agent may not set
 *
 * `authSettingName` and `autoGrant` are absent from [Input] on purpose, and
 * [SelfConfigToolService.create] forces them to `null` / `false`. Binding a stored credential to a
 * configuration, and handing a configuration to every agent in scope without any agent naming it,
 * are decisions a human makes in the configuration interface; they are not self-service. Leaving
 * them out of the schema means the LLM cannot even express them, so there is nothing to validate
 * and nothing to refuse.
 *
 * ## Confirmation
 *
 * [ConfirmationMode.INFER]: a creation is recoverable (the user can delete it), so an explicit
 * prompt is skipped when the conversation already shows consent. Note that the gate is honoured by
 * [io.whozoss.agentos.agent.AgentAdvanced] only -- for a simple agent it is a safeguard that does
 * not fire, which is why every rule that actually *protects* data lives in
 * [SelfConfigToolService], not here.
 */
class CreateIntegrationTool(
    configName: String? = null,
    private val creator: (
        context: ToolContext,
        name: String,
        integrationType: String,
        description: String?,
        parameters: JsonNode?,
        namespaceScoped: Boolean,
    ) -> SelfConfigOutcome<IntegrationConfig>,
) : StandardTool<CreateIntegrationTool.Input> {
    /**
     * @param name Unique name within the user's scope; also the prefix of the tool names the
     *   resulting integration produces (`MY_JIRA__GetIssue`).
     * @param integrationType One of the types returned by `listIntegrationTypes`.
     * @param parameters Object matching that type's `configSchema`.
     * @param scope `"namespace"` (default) binds the configuration to the current namespace;
     *   `"global"` makes it follow the user into every namespace.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val name: String? = null,
        val integrationType: String? = null,
        val description: String? = null,
        val parameters: JsonNode? = null,
        val scope: String? = null,
    )

    override val name: String = configName?.let { "${it}__createIntegration" } ?: "createIntegration"

    override val description: String =
        """
        Create a personal integration configuration for the user.

        Call 'listIntegrationTypes' first to get the exact parameter schema of the type you intend
        to use, then supply 'parameters' matching that schema.

        'scope' decides where the configuration applies:
        - "namespace" (default): only in the current workspace.
        - "global": for this user in every workspace.

        The configuration belongs to the user alone: it never affects anybody else, and it takes
        precedence over a shared configuration of the same name for this user's runs.

        You cannot attach credentials or make the configuration apply automatically to every agent —
        those are administrator operations done in the configuration interface.

        A new configuration is picked up on the user's next run, not in the current conversation.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Name of the configuration, unique among the user's own ones. Also used as the prefix of the tool names it produces, so prefer a short uppercase identifier (e.g. 'MY_JIRA')."
            },
            "integrationType": {
              "type": "string",
              "description": "Integration type identifier, exactly as returned by listIntegrationTypes."
            },
            "description": {
              "type": "string",
              "description": "Optional short description shown to agents that receive this integration."
            },
            "parameters": {
              "type": "object",
              "description": "Configuration parameters, matching the configSchema of the chosen integrationType."
            },
            "scope": {
              "type": "string",
              "enum": ["namespace", "global"],
              "description": "Where the configuration applies. 'namespace' (default) = current workspace only; 'global' = this user in every workspace.",
              "default": "namespace"
            }
          },
          "required": ["name", "integrationType"]
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override fun outputSchema(): JsonNode = SelfConfigRendering.CONFIG_SCHEMA

    override suspend fun getConfirmationMode(
        argsJson: String?,
        context: ToolContext?,
    ): ConfirmationMode = ConfirmationMode.INFER

    override fun getConfirmationInstructions(): String =
        "Creating a configuration only affects this user and can be undone by deleting it. " +
            "A clear request to set up, connect or configure an integration is sufficient consent; " +
            "a question about what is possible is not."

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val name = input?.name?.trim()?.takeIf { it.isNotBlank() }
        val type = input?.integrationType?.trim()?.takeIf { it.isNotBlank() }
        if (name == null || type == null) {
            return ToolExecutionResult.error(
                "'name' and 'integrationType' are both required.",
                errorType = SelfConfigErrors.MISSING_INPUT,
            )
        }
        val scope = input?.scope?.trim()?.lowercase() ?: SCOPE_NAMESPACE
        if (scope != SCOPE_NAMESPACE && scope != SCOPE_GLOBAL) {
            return ToolExecutionResult.error(
                "'scope' must be either '$SCOPE_NAMESPACE' or '$SCOPE_GLOBAL'.",
                errorType = SelfConfigErrors.INVALID_INPUT,
            )
        }
        val parameters = input?.parameters?.takeUnless { it.isNull }

        return when (
            val outcome =
                creator(context, name, type, input?.description, parameters, scope == SCOPE_NAMESPACE)
        ) {
            is SelfConfigOutcome.Failure -> SelfConfigRendering.toError(outcome)
            is SelfConfigOutcome.Ok ->
                ToolExecutionResult.success(
                    output = "Created personal integration configuration ${SelfConfigRendering.summarize(outcome.value)}.",
                    structuredOutput = SelfConfigRendering.toNode(outcome.value),
                )
        }
    }

    companion object {
        const val SCOPE_NAMESPACE = "namespace"
        const val SCOPE_GLOBAL = "global"
    }
}
