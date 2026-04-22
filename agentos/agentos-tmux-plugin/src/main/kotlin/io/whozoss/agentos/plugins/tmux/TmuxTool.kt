package io.whozoss.agentos.plugins.tmux

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import java.util.concurrent.TimeUnit

/** Regex for safe session names: alphanumeric, hyphens, underscores only. */
private val VALID_SESSION_NAME = Regex("^[a-zA-Z0-9_-]+$")

/** Regex for safe window names: alphanumeric, hyphens, underscores, dots, plus signs. */
private val VALID_WINDOW_NAME = Regex("^[a-zA-Z0-9_.+-]+$")

/** Timeout in seconds for any single tmux subprocess call. */
private const val TMUX_TIMEOUT_SECONDS = 30L

/**
 * Tool that manages long-running processes in persistent tmux sessions.
 *
 * Sessions survive across agent interactions and can be inspected at any time.
 * [workingDirectory] is injected at construction time from the plugin's
 * IntegrationConfig parameters. New sessions are created in that directory;
 * when null the user's home directory is used (tmux default).
 */
class TmuxTool(
    internal val workingDirectory: String? = null,
    configName: String? = null,
) : StandardTool<TmuxTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
    }

    override val name: String = when (configName) {
        null -> "Tmux"
        else -> "${configName}__Tmux"
    }

    override val description: String =
        """
        Manage long-running processes in persistent tmux sessions.
        Sessions survive across agent interactions and can be inspected at any time.

        Actions:
        - list: show all active tmux sessions
        - status <session>: check if a specific session is running ("running" or "stopped")
        - start <session> <command>: launch a command in a named session (creates session if needed)
        - logs <session>: read recent output from a session (last ~200 lines)
        - send <session> <command>: send a command to an already running session
        - stop <session>: kill a session and its processes

        The optional 'window' parameter targets a specific window within a session (0-based index or name).
        When omitted, the currently active window is used.
        Use it when a session has multiple windows and you need to interact with a specific one.

        Use clear, stable session names matching the application role, e.g. "backend", "frontend", "worker".
        New sessions always start from the configured working directory.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        """
        {
            "${"\$"}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["action"],
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["list", "status", "start", "logs", "send", "stop"],
                    "description": "The tmux action to perform."
                },
                "session": {
                    "type": "string",
                    "description": "Session name (required for all actions except list). Only alphanumeric characters, hyphens and underscores are allowed."
                },
                "window": {
                    "type": "string",
                    "description": "Optional window index (0-based) or window name within the session. When omitted, the currently active window is used. Relevant for logs, send, and start actions."
                },
                "command": {
                    "type": "string",
                    "description": "Command to run (required for start and send actions)."
                }
            },
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val action: String,
        val session: String? = null,
        val window: String? = null,
        val command: String? = null,
    )

    private enum class Action {
        LIST {
            override fun execute(input: Input, tool: TmuxTool): String =
                tool.runTmux("list-sessions")
                    .fold(
                        onSuccess = { output -> tool.createSuccessResponse(output.ifBlank { "No tmux sessions running" }) },
                        onFailure = { tool.createSuccessResponse("No tmux sessions running") },
                    )
        },
        STATUS {
            override fun execute(input: Input, tool: TmuxTool): String =
                when (val session = input.session) {
                    null -> tool.createErrorResponse("session is required for action 'status'")
                    else -> tool.runTmux("has-session", "-t", session)
                        .fold(
                            onSuccess = { tool.createSuccessResponse("running") },
                            onFailure = { tool.createSuccessResponse("stopped") },
                        )
                }
        },
        START {
            override fun execute(input: Input, tool: TmuxTool): String =
                when {
                    input.session == null -> tool.createErrorResponse("session is required for action 'start'")
                    input.command == null -> tool.createErrorResponse("command is required for action 'start'")
                    else -> {
                        val newSessionArgs =
                            buildList {
                                addAll(listOf("new-session", "-d", "-s", input.session, "-x", "220", "-y", "50"))
                                if (tool.workingDirectory != null) addAll(listOf("-c", tool.workingDirectory))
                            }
                        // Ignore error: session may already exist
                        tool.runTmux(*newSessionArgs.toTypedArray())
                        val target = tool.buildTarget(input.session, input.window)
                        tool.runTmux("send-keys", "-t", target, input.command, "Enter")
                            .fold(
                                onSuccess = { tool.createSuccessResponse("Session '${input.session}' started") },
                                onFailure = { e -> tool.createErrorResponse("Failed to start session '${input.session}': ${e.message}") },
                            )
                    }
                }
        },
        LOGS {
            override fun execute(input: Input, tool: TmuxTool): String =
                when (val session = input.session) {
                    null -> tool.createErrorResponse("session is required for action 'logs'")
                    else -> {
                        val target = tool.buildTarget(session, input.window)
                        tool.runTmux("capture-pane", "-t", target, "-p", "-S", "-200")
                            .fold(
                                onSuccess = { output -> tool.createSuccessResponse(output) },
                                onFailure = { tool.createErrorResponse("Session '$session' not found") },
                            )
                    }
                }
        },
        SEND {
            override fun execute(input: Input, tool: TmuxTool): String =
                when {
                    input.session == null -> tool.createErrorResponse("session is required for action 'send'")
                    input.command == null -> tool.createErrorResponse("command is required for action 'send'")
                    else -> {
                        val target = tool.buildTarget(input.session, input.window)
                        tool.runTmux("send-keys", "-t", target, input.command, "Enter")
                            .fold(
                                onSuccess = { tool.createSuccessResponse("Command sent to session '${input.session}'") },
                                onFailure = { tool.createErrorResponse("Session '${input.session}' not found") },
                            )
                    }
                }
        },
        STOP {
            override fun execute(input: Input, tool: TmuxTool): String =
                when (val session = input.session) {
                    null -> tool.createErrorResponse("session is required for action 'stop'")
                    else -> tool.runTmux("kill-session", "-t", session)
                        .fold(
                            onSuccess = { tool.createSuccessResponse("Session '$session' killed") },
                            onFailure = { tool.createErrorResponse("Session '$session' not found") },
                        )
                }
        };

        abstract fun execute(input: Input, tool: TmuxTool): String

        companion object {
            fun fromString(value: String): Action? =
                entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }

    override fun execute(input: Input?): String {
        val validationError = when {
            input == null -> "Input is required"
            input.session != null && !VALID_SESSION_NAME.matches(input.session) ->
                "Invalid session name '${input.session}'. Only alphanumeric characters, hyphens and underscores are allowed."
            input.window != null && !VALID_WINDOW_NAME.matches(input.window) ->
                "Invalid window name '${input.window}'. Only alphanumeric characters, hyphens, underscores, dots and plus signs are allowed."
            else -> null
        }
        if (validationError != null) return createErrorResponse(validationError)
        val action = Action.fromString(input!!.action)
            ?: return createErrorResponse("Unknown action: ${input.action}")
        return action.execute(input, this)
    }

    /**
     * Builds a tmux target string: `session` when window is null, `session:window` otherwise.
     * tmux accepts both window index ("0", "1") and window name as the window part.
     */
    internal fun buildTarget(
        session: String,
        window: String?,
    ): String = when (window) {
        null -> session
        else -> "$session:$window"
    }

    /**
     * Runs a tmux command and returns its stdout on success, or a [Throwable] on non-zero exit.
     * Uses [ProcessBuilder] to avoid shell injection.
     * Enforces a [TMUX_TIMEOUT_SECONDS]-second timeout; kills the process forcibly on expiry.
     */
    internal fun runTmux(vararg args: String): Result<String> =
        runCatching {
            val process =
                ProcessBuilder("tmux", *args)
                    .redirectErrorStream(true)
                    .start()
            val timedOut = !process.waitFor(TMUX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (timedOut) {
                process.destroyForcibly()
                throw RuntimeException("tmux command timed out after ${TMUX_TIMEOUT_SECONDS}s")
            }
            // Stream is fully available once waitFor() returns — safe to read before checking exit code.
            // Reading here (rather than only on failure) avoids double-buffering: the same output
            // string is used for both the success return and the error message.
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()
            if (exitCode != 0) throw RuntimeException(output.trim().ifBlank { "tmux exited with code $exitCode" })
            output
        }

    internal fun createSuccessResponse(output: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "success" to true,
                "output" to output,
            ),
        )

    internal fun createErrorResponse(message: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "success" to false,
                "error" to message,
            ),
        )
}
