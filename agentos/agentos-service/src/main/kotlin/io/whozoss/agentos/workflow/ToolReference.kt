package io.whozoss.agentos.workflow

/**
 * How a workflow step names its tool: bare (`listAgentUsers`) or prefixed by the integration config
 * that grants it (`USER_GROUP__listAgentUsers`). The one matching rule shared by the validator, the
 * wiring, the runner and the input validator, so they can never disagree on what a name refers to.
 */
object ToolReference {
    /** Separator between an integration config name and a tool name. */
    const val PREFIX_SEPARATOR = "__"

    /** Whether the granted tool [toolName] is the one [reference] names. Case-sensitive. */
    fun matches(
        toolName: String,
        reference: String,
    ): Boolean = toolName == reference || toolName.endsWith(PREFIX_SEPARATOR + reference)

    /** Whether [reference] (bare or prefixed) refers to the tool whose bare name is [bareName]. */
    fun refersTo(
        reference: String,
        bareName: String,
    ): Boolean = matches(reference, bareName)
}
