package io.whozoss.agentos.plugins.tmux

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool

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
    companion object {
        private val objectMapper = jacksonObjectMapper()
    }

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

    override fun execute(input: Input?): String {
        if (input == null) return createErrorResponse("Input is required")
        if (input.seconds < 1 || input.seconds > MAX_WAIT_SECONDS) {
            return createErrorResponse("seconds must be between 1 and $MAX_WAIT_SECONDS, got ${input.seconds}")
        }
        Thread.sleep(input.seconds * 1000L)
        return createSuccessResponse("Waited ${input.seconds} second${if (input.seconds == 1) "" else "s"}")
    }

    private fun createSuccessResponse(output: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "success" to true,
                "output" to output,
            ),
        )

    private fun createErrorResponse(message: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "success" to false,
                "error" to message,
            ),
        )
}
