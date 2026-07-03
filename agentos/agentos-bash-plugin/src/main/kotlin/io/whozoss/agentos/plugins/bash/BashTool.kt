package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import mu.KLogging
import java.io.File

/**
 * A [StandardTool] that executes a configured bash command.
 *
 * Two modes depending on whether the command contains [PARAMETERS_PLACEHOLDER]:
 *
 * - **Fixed command** (`PARAMETERS` absent): the tool takes no input. The LLM calls it
 *   with an empty object `{}`. The command runs as-is.
 *
 * - **Parameterised command** (`PARAMETERS` present): the tool exposes a single required
 *   `parameters` string field. At runtime the literal string `PARAMETERS` in the command
 *   is replaced by the value supplied by the LLM.
 *
 * The `inputSchema` is generated accordingly so the LLM sees the right contract.
 */
class BashTool(
    private val toolConfig: BashToolConfig,
    private val integrationConfig: BashIntegrationConfig,
    configName: String? = null,
) : StandardTool<BashTool.Input> {
    private val isParameterised: Boolean = toolConfig.command.contains(PARAMETERS_PLACEHOLDER)

    override val name: String = if (configName != null) "${configName}__${toolConfig.name}" else toolConfig.name

    override val description: String =
        buildString {
            append(toolConfig.description)
            if (isParameterised) {
                append("\n\nParameters: ")
                append(toolConfig.parametersDescription)
            }
        }

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        when {
            isParameterised ->
                $$"""
                {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {
                        "parameters": {
                            "type": "string",
                            "description": $${objectMapper.writeValueAsString(toolConfig.parametersDescription)}
                        }
                    },
                    "required": ["parameters"],
                    "additionalProperties": false
                }
                """.trimIndent()
            else ->
                $$"""
                {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {},
                    "additionalProperties": false
                }
                """.trimIndent()
        }

    data class Input(
        val parameters: String? = null,
    )

    companion object : KLogging() {
        private val objectMapper = jacksonObjectMapper()
    }

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        val resolvedCommand =
            resolveCommand(input)
                ?: return ToolExecutionResult.error(
                    "Error: tool '${toolConfig.name}' requires a 'parameters' value but none was provided",
                    errorType = "INVALID_INPUT",
                )

        val workDir = resolveWorkingDirectory()
        val timeout = toolConfig.timeoutSeconds ?: integrationConfig.defaultTimeoutSeconds

        logger.debug { "Executing tool '${toolConfig.name}' in ${workDir.absolutePath} (timeout: ${timeout}s)" }

        return when (val result = BashCommandExecutor.execute(resolvedCommand, workDir, timeout)) {
            is BashExecutionResult.Completed ->
                when {
                    result.exitCode != 0 -> ToolExecutionResult.error(
                        output = formatCompleted(result),
                        errorType = "NONZERO_EXIT",
                        errorMessage = "Command exited with code ${result.exitCode}",
                    )
                    else -> ToolExecutionResult.success(formatCompleted(result))
                }
            is BashExecutionResult.Timeout -> ToolExecutionResult.error(
                output = "Error: command timed out after ${result.timeoutSeconds} seconds",
                errorType = "TIMEOUT",
                errorMessage = "Command timed out after ${result.timeoutSeconds} seconds",
            )
            is BashExecutionResult.Error -> ToolExecutionResult.error(
                output = "Error: ${result.message}",
                errorType = "EXECUTION_ERROR",
                errorMessage = result.message,
            )
        }
    }

    /**
     * Returns the final command string with [PARAMETERS_PLACEHOLDER] substituted,
     * or null if the tool is parameterised but no valid parameters value was supplied.
     */
    private fun resolveCommand(input: Input?): String? =
        when {
            !isParameterised -> toolConfig.command
            else ->
                input
                    ?.parameters
                    ?.takeIf { it.isNotBlank() }
                    ?.let { toolConfig.command.replace(PARAMETERS_PLACEHOLDER, it) }
        }

    private fun resolveWorkingDirectory(): File {
        val base = File(integrationConfig.workingDirectory)
        return when {
            toolConfig.path.isNullOrBlank() -> base
            else -> File(base, toolConfig.path)
        }
    }

    private fun formatCompleted(result: BashExecutionResult.Completed): String =
        buildString {
            if (result.stdout.isNotBlank()) {
                append(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Stderr:\n")
                append(result.stderr)
            }
            if (result.exitCode != 0) {
                if (isNotEmpty()) append("\n\n")
                append("Exit code: ${result.exitCode}")
            }
            if (isEmpty()) {
                append("(no output)")
            }
        }
}
