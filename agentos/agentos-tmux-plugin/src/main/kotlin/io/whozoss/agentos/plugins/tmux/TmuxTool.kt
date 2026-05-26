package io.whozoss.agentos.plugins.tmux

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
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
    override val name: String =
        when (configName) {
            null -> "Tmux"
            else -> "${configName}__Tmux"
        }

    override val description: String =
        """
        Manage long-running processes in persistent tmux sessions.
        Sessions survive across agent interactions and can be inspected at any time.

        SESSION-LEVEL ACTIONS (operate on the whole session):
        - list: show all active tmux sessions with their windows
        - status <session>: check if a specific session exists ("running" or "stopped")
        - start <session> <command>: create a new session and run a command in its first window
        - stop <session>: kill an entire session and all its windows/processes

        WINDOW-LEVEL ACTIONS (operate on a specific window within a session):
        - new-window <session> <command>: create a new window in an existing session and run a command in it
          Use the optional 'window' parameter to give the new window a name.
        - close-window <session> <window>: close a specific window within a session (window param required)
        - send <session> <command>: send a command to a running window (defaults to active window)
        - logs <session>: read recent output from a window (defaults to active window, last ~200 lines)

        The optional 'window' parameter targets a specific window within a session.
        It accepts either a 0-based index ("0", "1", ...) or a window name.
        When omitted, the currently active window is used.

        TYPICAL WORKFLOW:
          1. start "myapp" "./gradlew bootRun"   -> creates session with window 0
          2. new-window "myapp" "./npm run dev"   -> adds window 1 to the same session
          3. logs "myapp" window="1"              -> reads output of window 1
          4. stop "myapp"                         -> kills the entire session

        Use clear, stable session names matching the application role, e.g. "backend", "frontend", "worker".
        New sessions always start from the configured working directory.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        $$"""
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "required": ["action"],
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["list", "status", "start", "stop", "new-window", "close-window", "send", "logs"],
                    "description": "The tmux action to perform. Session-level: list, status, start, stop. Window-level: new-window, close-window, send, logs."
                },
                "session": {
                    "type": "string",
                    "description": "Session name (required for all actions except list). Only alphanumeric characters, hyphens and underscores are allowed."
                },
                "window": {
                    "type": "string",
                    "description": "Window index (0-based) or window name within the session. Required for close-window. Optional for send, logs, and new-window (used as the new window name when provided). When omitted, the currently active window is used."
                },
                "command": {
                    "type": "string",
                    "description": "Command to run (required for start, new-window, and send actions)."
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
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                tool
                    .runTmux("list-sessions")
                    .fold(
                        onSuccess = { output -> ToolExecutionResult.success(output.ifBlank { "No tmux sessions running" }) },
                        onFailure = { ToolExecutionResult.success("No tmux sessions running") },
                    )
        },
        STATUS {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when (val session = input.session) {
                    null -> ToolExecutionResult.error("session is required for action 'status'", errorType = "INVALID_INPUT")
                    else ->
                        tool
                            .runTmux("has-session", "-t", session)
                            .fold(
                                onSuccess = { ToolExecutionResult.success("running") },
                                onFailure = { ToolExecutionResult.success("stopped") },
                            )
                }
        },
        START {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when {
                    input.session == null -> ToolExecutionResult.error("session is required for action 'start'", errorType = "INVALID_INPUT")
                    input.command == null -> ToolExecutionResult.error("command is required for action 'start'", errorType = "INVALID_INPUT")
                    else -> {
                        val newSessionArgs =
                            buildList {
                                addAll(listOf("new-session", "-d", "-s", input.session, "-x", "220", "-y", "50"))
                                if (tool.workingDirectory != null) addAll(listOf("-c", tool.workingDirectory))
                            }
                        // Ignore error: session may already exist
                        tool.runTmux(*newSessionArgs.toTypedArray())
                        tool
                            .runTmux("send-keys", "-t", "${input.session}:0", input.command, "Enter")
                            .fold(
                                onSuccess = { ToolExecutionResult.success("Session '${input.session}' started with window 0") },
                                onFailure = { e ->
                                    ToolExecutionResult.error(
                                        "Failed to start session '${input.session}': ${e.message}",
                                        errorType = "COMMAND_FAILED",
                                        errorMessage = e.message,
                                    )
                                },
                            )
                    }
                }
        },
        STOP {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when (val session = input.session) {
                    null -> ToolExecutionResult.error("session is required for action 'stop'", errorType = "INVALID_INPUT")
                    else ->
                        tool
                            .runTmux("kill-session", "-t", session)
                            .fold(
                                onSuccess = { ToolExecutionResult.success("Session '$session' killed") },
                                onFailure = { ToolExecutionResult.error("Session '$session' not found", errorType = "SESSION_NOT_FOUND") },
                            )
                }
        },
        NEW_WINDOW {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when {
                    input.session == null -> ToolExecutionResult.error("session is required for action 'new-window'", errorType = "INVALID_INPUT")
                    input.command == null -> ToolExecutionResult.error("command is required for action 'new-window'", errorType = "INVALID_INPUT")
                    else -> {
                        val args =
                            buildList {
                                addAll(listOf("new-window", "-t", input.session))
                                if (input.window != null) addAll(listOf("-n", input.window))
                                if (tool.workingDirectory != null) addAll(listOf("-c", tool.workingDirectory))
                            }
                        val createResult = tool.runTmux(*args.toTypedArray())
                        val windowDesc = if (input.window != null) "'${input.window}'" else "a new window"
                        when {
                            createResult.isFailure ->
                                ToolExecutionResult.error(
                                    "Failed to create window in session '${input.session}': ${createResult.exceptionOrNull()?.message}",
                                    errorType = "COMMAND_FAILED",
                                    errorMessage = createResult.exceptionOrNull()?.message,
                                )
                            else ->
                                tool
                                    .runTmux("send-keys", "-t", tool.buildTarget(input.session, input.window), input.command, "Enter")
                                    .fold(
                                        onSuccess = { ToolExecutionResult.success("Created $windowDesc in session '${input.session}'") },
                                        onFailure = { e ->
                                            ToolExecutionResult.error(
                                                "Failed to create window in session '${input.session}': ${e.message}",
                                                errorType = "COMMAND_FAILED",
                                                errorMessage = e.message,
                                            )
                                        },
                                    )
                        }
                    }
                }
        },
        CLOSE_WINDOW {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when {
                    input.session == null -> ToolExecutionResult.error("session is required for action 'close-window'", errorType = "INVALID_INPUT")
                    input.window == null -> ToolExecutionResult.error("window is required for action 'close-window'", errorType = "INVALID_INPUT")
                    else -> {
                        val target = tool.buildTarget(input.session, input.window)
                        tool
                            .runTmux("kill-window", "-t", target)
                            .fold(
                                onSuccess = { ToolExecutionResult.success("Window '${input.window}' in session '${input.session}' closed") },
                                onFailure = { e ->
                                    ToolExecutionResult.error(
                                        "Failed to close window '${input.window}' in session '${input.session}': ${e.message}",
                                        errorType = "COMMAND_FAILED",
                                        errorMessage = e.message,
                                    )
                                },
                            )
                    }
                }
        },
        LOGS {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when (val session = input.session) {
                    null -> ToolExecutionResult.error("session is required for action 'logs'", errorType = "INVALID_INPUT")
                    else -> {
                        val target = tool.buildTarget(session, input.window)
                        tool
                            .runTmux("capture-pane", "-t", target, "-p", "-S", "-200")
                            .fold(
                                onSuccess = { output -> ToolExecutionResult.success(output) },
                                onFailure = { ToolExecutionResult.error("Session '$session' not found", errorType = "SESSION_NOT_FOUND") },
                            )
                    }
                }
        },
        SEND {
            override fun execute(
                input: Input,
                tool: TmuxTool,
            ): ToolExecutionResult =
                when {
                    input.session == null -> ToolExecutionResult.error("session is required for action 'send'", errorType = "INVALID_INPUT")
                    input.command == null -> ToolExecutionResult.error("command is required for action 'send'", errorType = "INVALID_INPUT")
                    else -> {
                        val target = tool.buildTarget(input.session, input.window)
                        tool
                            .runTmux("send-keys", "-t", target, input.command, "Enter")
                            .fold(
                                onSuccess = { ToolExecutionResult.success("Command sent to session '${input.session}'") },
                                onFailure = { ToolExecutionResult.error("Session '${input.session}' not found", errorType = "SESSION_NOT_FOUND") },
                            )
                    }
                }
        }, ;

        abstract fun execute(
            input: Input,
            tool: TmuxTool,
        ): ToolExecutionResult

        companion object {
            fun fromString(value: String): Action? = entries.firstOrNull { it.name.replace('_', '-').equals(value, ignoreCase = true) }
        }
    }

    override suspend fun execute(input: Input?, context: ToolContext): ToolExecutionResult {
        val validationError =
            when {
                input == null -> "Input is required"
                input.session != null && !VALID_SESSION_NAME.matches(input.session) ->
                    "Invalid session name '${input.session}'. Only alphanumeric characters, hyphens and underscores are allowed."
                input.window != null && !VALID_WINDOW_NAME.matches(input.window) ->
                    "Invalid window name '${input.window}'. Only alphanumeric characters, hyphens, underscores, dots and plus signs are allowed."
                else -> null
            }
        if (validationError != null) return ToolExecutionResult.error(validationError, errorType = "INVALID_INPUT")
        val action =
            Action.fromString(input!!.action)
                ?: return ToolExecutionResult.error("Unknown action: ${input.action}", errorType = "INVALID_INPUT")
        return action.execute(input, this)
    }

    /**
     * Builds a tmux target string: `session` when window is null, `session:window` otherwise.
     * tmux accepts both window index ("0", "1") and window name as the window part.
     */
    internal fun buildTarget(
        session: String,
        window: String?,
    ): String =
        when (window) {
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

}
