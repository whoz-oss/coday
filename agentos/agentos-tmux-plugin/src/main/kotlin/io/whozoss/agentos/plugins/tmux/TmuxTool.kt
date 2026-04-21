package io.whozoss.agentos.plugins.tmux

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool

/** Regex for safe session names: alphanumeric, hyphens, underscores only. */
private val VALID_SESSION_NAME = Regex("^[a-zA-Z0-9_-]+$")

/**
 * Tool that manages long-running processes in persistent tmux sessions.
 *
 * Sessions survive across agent interactions and can be inspected at any time.
 * [workingDirectory] is injected at construction time from the plugin's
 * IntegrationConfig parameters. New sessions are created in that directory;
 * when null the user's home directory is used (tmux default).
 */
class TmuxTool(
    private val workingDirectory: String? = null,
    configName: String? = null,
) : StandardTool<TmuxTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
    }

    override val name: String = if (configName != null) "${configName}__Tmux" else "Tmux"

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

    override fun execute(input: Input?): String {
        if (input == null) return createErrorResponse("Input is required")

        val sessionError = input.session?.let { validateSession(it) }
        if (sessionError != null) return createErrorResponse(sessionError)

        return when (input.action) {
            "list" -> executeList()
            "status" -> executeStatus(input.session)
            "start" -> executeStart(input.session, input.window, input.command)
            "logs" -> executeLogs(input.session, input.window)
            "send" -> executeSend(input.session, input.window, input.command)
            "stop" -> executeStop(input.session)
            else -> createErrorResponse("Unknown action: ${input.action}")
        }
    }

    private fun validateSession(session: String): String? =
        when {
            !VALID_SESSION_NAME.matches(session) ->
                "Invalid session name '$session'. Only alphanumeric characters, hyphens and underscores are allowed."
            else -> null
        }

    private fun executeList(): String =
        runTmux("list-sessions")
            .fold(
                onSuccess = { output -> createSuccessResponse(output.ifBlank { "No tmux sessions running" }) },
                onFailure = { createSuccessResponse("No tmux sessions running") },
            )

    private fun executeStatus(session: String?): String {
        if (session == null) return createErrorResponse("session is required for action 'status'")
        return runTmux("has-session", "-t", session)
            .fold(
                onSuccess = { createSuccessResponse("running") },
                onFailure = { createSuccessResponse("stopped") },
            )
    }

    private fun executeStart(
        session: String?,
        window: String?,
        command: String?,
    ): String {
        if (session == null) return createErrorResponse("session is required for action 'start'")
        if (command == null) return createErrorResponse("command is required for action 'start'")

        val newSessionArgs =
            buildList {
                addAll(listOf("new-session", "-d", "-s", session, "-x", "220", "-y", "50"))
                if (workingDirectory != null) addAll(listOf("-c", workingDirectory))
            }
        // Ignore error: session may already exist
        runTmux(*newSessionArgs.toTypedArray())
        val target = buildTarget(session, window)
        return runTmux("send-keys", "-t", target, command, "Enter")
            .fold(
                onSuccess = { createSuccessResponse("Session '$session' started") },
                onFailure = { e -> createErrorResponse("Failed to start session '$session': ${e.message}") },
            )
    }

    private fun executeLogs(
        session: String?,
        window: String?,
    ): String {
        if (session == null) return createErrorResponse("session is required for action 'logs'")
        val target = buildTarget(session, window)
        return runTmux("capture-pane", "-t", target, "-p", "-S", "-200")
            .fold(
                onSuccess = { output -> createSuccessResponse(output) },
                onFailure = { createErrorResponse("Session '$session' not found") },
            )
    }

    private fun executeSend(
        session: String?,
        window: String?,
        command: String?,
    ): String {
        if (session == null) return createErrorResponse("session is required for action 'send'")
        if (command == null) return createErrorResponse("command is required for action 'send'")
        val target = buildTarget(session, window)
        return runTmux("send-keys", "-t", target, command, "Enter")
            .fold(
                onSuccess = { createSuccessResponse("Command sent to session '$session'") },
                onFailure = { createErrorResponse("Session '$session' not found") },
            )
    }

    private fun executeStop(session: String?): String {
        if (session == null) return createErrorResponse("session is required for action 'stop'")
        return runTmux("kill-session", "-t", session)
            .fold(
                onSuccess = { createSuccessResponse("Session '$session' killed") },
                onFailure = { createErrorResponse("Session '$session' not found") },
            )
    }

    /**
     * Builds a tmux target string: `session` when window is null, `session:window` otherwise.
     * tmux accepts both window index ("0", "1") and window name as the window part.
     */
    private fun buildTarget(
        session: String,
        window: String?,
    ): String = if (window != null) "$session:$window" else session

    /**
     * Runs a tmux command and returns its stdout on success, or a [Throwable] on non-zero exit.
     * Uses [ProcessBuilder] to avoid shell injection.
     */
    private fun runTmux(vararg args: String): Result<String> =
        runCatching {
            val process =
                ProcessBuilder("tmux", *args)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) throw RuntimeException(output.trim().ifBlank { "tmux exited with code $exitCode" })
            output
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
