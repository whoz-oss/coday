package io.whozoss.agentos.agent

/**
 * The fixed wording used to tell the agent and the case that integrations failed to load.
 *
 * It is built from integration **names** only, never from an exception, so no secret can reach the model or
 * the case through it (see [io.whozoss.agentos.tool.ToolResolution]). The texts are fixed on purpose: a run
 * where nothing failed carries nothing, which keeps the prompt-cache prefix unchanged.
 */
internal object IntegrationLoadFailureNotice {
    /**
     * Appends the failure block to [instructions], at the end so the cached prefix of the prompt is untouched.
     * The names are sorted so the same failures always produce the same text.
     * Returns [instructions] as it is when [failedIntegrationNames] is empty.
     */
    fun appendTo(
        instructions: String,
        failedIntegrationNames: List<String>,
    ): String =
        if (failedIntegrationNames.isEmpty()) {
            instructions
        } else {
            listOfNotNull(
                instructions.takeUnless { it.isBlank() },
                instructionsBlock(failedIntegrationNames = failedIntegrationNames.sorted()),
            ).joinToString(separator = "\n")
        }

    /** The message of the [io.whozoss.agentos.sdk.caseEvent.WarnEvent] emitted for [integrationName]. */
    fun warnMessage(integrationName: String): String =
        "Integration $integrationName could not be loaded for this message. " +
            "An administrator can find the cause in the server logs."

    private fun instructionsBlock(failedIntegrationNames: List<String>): String =
        buildString {
            appendLine()
            appendLine("## Integration load failures")
            append(
                "Integrations that failed to load in this run, their tools are missing: " +
                    "${failedIntegrationNames.joinToString(separator = ", ")}. " +
                    "Tell the user which ones are unavailable and that the cause is in the server logs. " +
                    "Do not guess a cause.",
            )
        }
}
