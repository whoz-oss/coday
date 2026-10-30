package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Deletes one of the calling user's own integration configurations.
 *
 * ## Confirmation
 *
 * [ConfirmationMode.EVERY_TIME], unlike create and update: the parameters of a deleted
 * configuration are not recoverable from the conversation, and a personal configuration deleted by
 * mistake silently re-exposes the shared one it was overriding -- a change of behaviour the user
 * did not ask for and would struggle to attribute. Implicit consent is never trusted for that.
 *
 * The gate is honoured by [io.whozoss.agentos.agent.AgentAdvanced] only; for a simple agent it does
 * not fire. The protection that always holds is the ownership check in
 * [SelfConfigToolService.delete], which refuses any configuration the user does not own.
 */
class DeleteIntegrationTool(
    configName: String? = null,
    private val deleter: (context: ToolContext, id: UUID) -> SelfConfigOutcome<UUID>,
) : StandardTool<DeleteIntegrationTool.Input> {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Input(
        val id: String? = null,
    )

    override val name: String = configName?.let { "${it}__deleteIntegration" } ?: "deleteIntegration"

    override val description: String =
        """
        Delete one of the user's own personal integration configurations.

        Call 'listIntegrations' first to get the id, and make sure the user explicitly asked for the
        deletion: the parameters cannot be recovered afterwards.

        Note that deleting a personal configuration that shadowed a shared one of the same name
        restores the shared one for this user's runs — mention this to the user when it applies.

        Only personal configurations can be deleted. Shared ones belong to an administrator.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "format": "uuid",
              "description": "The id of the configuration to delete, as returned by listIntegrations."
            }
          },
          "required": ["id"]
        }
        """.trimIndent()

    override val version: String = "1.0.0"
    override val paramType: Class<Input> = Input::class.java

    override suspend fun getConfirmationMode(
        argsJson: String?,
        context: ToolContext?,
    ): ConfirmationMode = ConfirmationMode.EVERY_TIME

    override fun getConfirmationInstructions(): String =
        "Deletion is irreversible: the parameters cannot be recovered. It may also restore a shared " +
            "configuration of the same name that this one was overriding. Require an explicit, " +
            "unambiguous instruction naming the configuration to delete."

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val id =
            SelfConfigRendering.parseId(input?.id)
                ?: return ToolExecutionResult.error(
                    "A valid 'id' is required. Call listIntegrations to get the id of the configuration to delete.",
                    errorType = SelfConfigErrors.MISSING_INPUT,
                )

        return when (val outcome = deleter(context, id)) {
            is SelfConfigOutcome.Failure -> SelfConfigRendering.toError(outcome)
            is SelfConfigOutcome.Ok ->
                ToolExecutionResult.success(
                    output = "Deleted personal integration configuration $id.",
                    metadata = mapOf("deletedId" to id.toString()),
                )
        }
    }
}
