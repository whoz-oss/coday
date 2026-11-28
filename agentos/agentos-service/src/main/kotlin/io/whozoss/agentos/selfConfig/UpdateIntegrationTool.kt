package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Updates one of the calling user's own integration configurations.
 *
 * ## `parameters` replace, they do not merge
 *
 * The description says so twice, in the tool description and in the property description, because
 * it is the one thing an LLM gets wrong here: sending only the key it wants to change silently
 * drops every other key. The deep merge that does exist in AgentOS applies **between scope
 * layers** (platform under namespace under user), never between two versions of the same row.
 *
 * Fields the agent omits keep their persisted value -- [SelfConfigToolService.update] reloads the
 * row to do so, which is what stops an omitted field from being blanked.
 *
 * ## Confirmation
 *
 * [ConfirmationMode.INFER]: an update overwrites values the user may not be able to reconstruct,
 * so it is gated, but implicit consent in the conversation is accepted. As with the other write
 * tools, the gate only fires for [io.whozoss.agentos.agent.AgentAdvanced]; the authorization rules
 * that actually protect the data live in [SelfConfigToolService].
 */
class UpdateIntegrationTool(
    configName: String? = null,
    private val updater: (
        context: ToolContext,
        id: UUID,
        name: String?,
        description: String?,
        parameters: JsonNode?,
    ) -> SelfConfigOutcome<IntegrationConfig>,
) : StandardTool<UpdateIntegrationTool.Input> {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val id: String? = null,
        val name: String? = null,
        val description: String? = null,
        val parameters: JsonNode? = null,
    )

    override val name: String = configName?.let { "${it}__updateIntegration" } ?: "updateIntegration"

    override val description: String =
        """
        Update one of the user's own personal integration configurations.

        Call 'listIntegrations' first: you need the configuration's id, and you need its current
        parameters.

        IMPORTANT — 'parameters' REPLACES the whole parameter object; it is not merged. To change a
        single value, take the current parameters, modify that value, and send the complete object
        back. Sending only the changed key deletes every other key.

        Fields you omit keep their current value. You cannot change the integration type, the scope,
        the attached credentials, or whether the configuration applies automatically to every agent;
        to change the type or the scope, delete the configuration and create a new one.

        Changes apply to the user's next run, not to the current conversation.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "format": "uuid",
              "description": "The id of the configuration to update, as returned by listIntegrations."
            },
            "name": {
              "type": "string",
              "description": "New name. Omit to keep the current one. Renaming changes the prefix of the tool names this integration produces."
            },
            "description": {
              "type": "string",
              "description": "New description. Omit to keep the current one."
            },
            "parameters": {
              "type": "object",
              "description": "COMPLETE new parameter object; it replaces the current one entirely. Omit to keep the current parameters unchanged."
            }
          },
          "required": ["id"]
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
        "An update overwrites the configuration's parameters as a whole, and the previous values " +
            "cannot be recovered from this conversation. The user must have asked for this specific " +
            "change; discussing an integration is not consent to modify it."

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val id =
            SelfConfigRendering.parseId(input?.id)
                ?: return ToolExecutionResult.error(
                    "A valid 'id' is required. Call listIntegrations to get the id of the configuration to update.",
                    errorType = SelfConfigErrors.MISSING_INPUT,
                )
        val newName = input?.name?.trim()?.takeIf { it.isNotBlank() }
        val parameters = input?.parameters?.takeUnless { it.isNull }

        return when (val outcome = updater(context, id, newName, input?.description, parameters)) {
            is SelfConfigOutcome.Failure -> SelfConfigRendering.toError(outcome)
            is SelfConfigOutcome.Ok ->
                ToolExecutionResult.success(
                    output = "Updated personal integration configuration ${SelfConfigRendering.summarize(outcome.value)}.",
                    structuredOutput = SelfConfigRendering.toNode(outcome.value),
                )
        }
    }
}
