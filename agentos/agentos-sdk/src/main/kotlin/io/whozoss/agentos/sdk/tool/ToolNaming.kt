package io.whozoss.agentos.sdk.tool

/**
 * Name of a tool as the agent sees it: prefixed with `<configName>__` when the integration config has a name, so that
 * several configs of the same integration type do not clash. The same rule for every plugin.
 */
fun prefixedToolName(
    configName: String?,
    toolName: String,
): String =
    when (configName) {
        null -> toolName
        else -> "${configName}__$toolName"
    }
