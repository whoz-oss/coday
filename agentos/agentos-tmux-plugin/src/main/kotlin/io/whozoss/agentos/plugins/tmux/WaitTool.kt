package io.whozoss.agentos.plugins.tmux

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.delay

private const val MAX_WAIT_SECONDS = 30

/**
 * Tool that pauses execution for a configurable number of seconds.
 *
 * Intended for use when an agent needs to wait for a process to become ready
 * (e.g. after starting a server via [TmuxTool]) before proceeding with the
 * next action. The maximum wait is capped at [MAX_WAIT_SECONDS] seconds to
 * prevent accidental indefinite blocking.
 */
class WaitTool(
    configName: String? = null,
) : StandardTool<WaitTool.Input> {
    override val name: String = when (configName) {
        null -> "Wait"
        else -> "${configName}__Wait"
    }

    override val description: String =
        """
        Pause execution for a given number of seconds (maximum $MAX_WAIT_SECONDS).

        Use this after starting a long-running process with the Tmux tool to give it time
        to initialise before reading its logs or sending it further commands.
        For example: start a server, wait 5 seconds, then check the logs to confirm it is ready.

        The wait is capped at $MAX_WAIT_SECONDS seconds. For longer waits, call this tool
        multiple times or poll with status checks between calls.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        """
        {
            "${"\$"}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["seconds"],
            "properties": {
                "seconds": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": $MAX_WAIT_SECONDS,
                    "description": "Number of seconds to wait. Must be between 1 and $MAX_WAIT_SECONDS."
                }
            },
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val seconds: Int,
    )

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        if (input == null) return ToolExecutionResult.error("Input is required", errorType = "INVALID_INPUT")
        if (input.seconds < 1 || input.seconds > MAX_WAIT_SECONDS) {
            return ToolExecutionResult.error(
                "seconds must be between 1 and $MAX_WAIT_SECONDS, got ${input.seconds}",
                errorType = "INVALID_INPUT",
            )
        }
        delay(input.seconds * 1000L)
        return ToolExecutionResult.success("Waited ${input.seconds} second${if (input.seconds == 1) "" else "s"}")
    }
}
