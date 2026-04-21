package io.whozoss.agentos.plugins.bash

/**
 * Configuration for a single bash tool exposed to the LLM.
 *
 * @property name Tool name (used as the tool identifier — must be unique within the integration).
 * @property description Description sent to the LLM explaining when and how to use this tool.
 * @property command The bash command to execute. If it contains [PARAMETERS_PLACEHOLDER], the LLM
 *   must supply a value that will be substituted at runtime. If the command IS [PARAMETERS_PLACEHOLDER]
 *   alone, the LLM provides the entire command (raw bash mode — use with care).
 * @property parametersDescription Required when [command] contains [PARAMETERS_PLACEHOLDER].
 *   Describes to the LLM what it should pass as the parameters value.
 * @property path Optional subdirectory relative to the integration's [BashIntegrationConfig.workingDirectory].
 *   The command runs from `workingDirectory/path` when set.
 * @property timeoutSeconds Execution timeout in seconds. Overrides the integration-level default when set.
 */
data class BashToolConfig(
    val name: String,
    val description: String,
    val command: String,
    val parametersDescription: String? = null,
    val path: String? = null,
    val timeoutSeconds: Long? = null,
)

const val PARAMETERS_PLACEHOLDER = "PARAMETERS"

/**
 * Top-level configuration for the BASH integration.
 *
 * @property workingDirectory Absolute path to the base directory for all commands.
 * @property defaultTimeoutSeconds Default execution timeout in seconds for all tools in this
 *   integration. Individual tools may override with their own [BashToolConfig.timeoutSeconds].
 * @property tools List of bash tool definitions to expose.
 */
data class BashIntegrationConfig(
    val workingDirectory: String,
    val defaultTimeoutSeconds: Long = 30L,
    val tools: List<BashToolConfig> = emptyList(),
)
