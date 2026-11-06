package io.whozoss.agentos.claudeCode

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** The Claude Code conversation a turn runs in. AgentOS chooses the id, so it can resume it on the next turn. */
sealed interface ClaudeCodeSession {
    val id: UUID

    /** First turn of the conversation: the CLI records it under [id]. */
    data class Start(
        override val id: UUID,
    ) : ClaudeCodeSession

    /** Later turn: the CLI reloads the recorded conversation and appends to it. */
    data class Resume(
        override val id: UUID,
    ) : ClaudeCodeSession
}

/** The loopback MCP endpoint through which the CLI calls AgentOS tools during one turn. */
data class ClaudeCodeMcpServer(
    val url: String,
    val bearerToken: String,
    /** How long the CLI waits for one tool call: AgentOS tools such as delegation can run for minutes. */
    val toolTimeout: Duration,
) {
    override fun toString(): String = "ClaudeCodeMcpServer(url=$url, bearerToken=<redacted>, toolTimeout=$toolTimeout)"
}

/** Everything the CLI needs for one turn of one case. */
data class ClaudeCodeLaunchRequest(
    val userId: String,
    val caseId: String,
    /** A Claude Code model alias (`sonnet`, `opus`, `haiku`) or a full model name. */
    val model: String,
    val systemPrompt: String,
    val session: ClaudeCodeSession,
    val effort: ReasoningEffort? = null,
    /** Bound on the model's tool-use round trips in this turn; the CLI ends with `error_max_turns` when reached. */
    val maxTurns: Int? = null,
    /** Null runs the model without any tool. */
    val mcpServer: ClaudeCodeMcpServer? = null,
)

/**
 * Builds the command line, environment and directories of a Claude Code process. Pure, so the security
 * properties of the launch are asserted directly by tests rather than inferred from a child process.
 */
internal class ClaudeCodeCommand(
    private val properties: ClaudeCodeProperties,
) {
    /** `CLAUDE_CONFIG_DIR` of [userId]: the user's CLI login, settings and conversation transcripts. */
    fun configDirectory(userId: String): Path =
        properties.configRoot
            .toAbsolutePath()
            .normalize()
            .resolve(requireSafeSegment(userId, "user id"))

    /**
     * Working directory of the case's turns. It must stay the same across turns, because the CLI files a
     * conversation under its working directory, and stay empty: without `--bare`, `claude -p` runs the
     * hooks and MCP servers declared in the working directory's `.claude/` and `.mcp.json` without asking.
     * So it is never the case's Git worktree or Exchange directory.
     */
    fun workingDirectory(
        userId: String,
        caseId: String,
    ): Path = configDirectory(userId).resolve(WORKING_DIRECTORIES).resolve(requireSafeSegment(caseId, "case id"))

    fun loginCommand(userId: String): String = "CLAUDE_CONFIG_DIR=${shellQuote(configDirectory(userId).toString())} claude auth login"

    /**
     * The argument list after the executable. The system prompt and the MCP configuration, which carries
     * the bearer token, are passed as files: a command line is readable by every user of the host and a
     * single argument is limited to 128 KiB on Linux.
     */
    fun arguments(
        request: ClaudeCodeLaunchRequest,
        systemPromptFile: Path,
        mcpConfigFile: Path?,
    ): List<String> =
        buildList {
            add("-p")
            add("--input-format")
            add("stream-json")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            add("--model")
            add(request.model)
            add("--system-prompt-file")
            add(systemPromptFile.toString())
            // AgentOS rebuilds its instructions on every turn; a recorded prompt would hide the changes.
            add("--system-prompt-snapshot")
            add("off")
            // No built-in tool (Bash, Read, Edit...): the agent only gets AgentOS tools, through MCP.
            add("--tools")
            add("")
            add("--strict-mcp-config")
            if (mcpConfigFile != null) {
                add("--mcp-config")
                add(mcpConfigFile.toString())
                add("--allowedTools")
                add("$MCP_TOOL_PREFIX*")
            }
            // Neither the user's nor any project's settings, hooks, plugins, memory or CLAUDE.md files.
            add("--setting-sources")
            add("")
            add("--settings")
            add(SETTINGS_JSON)
            add("--disable-slash-commands")
            // Nobody answers permission prompts in a headless turn: anything not allowed above is denied.
            add("--permission-prompts")
            add("none")
            when (val session = request.session) {
                is ClaudeCodeSession.Start -> {
                    add("--session-id")
                    add(session.id.toString())
                }

                is ClaudeCodeSession.Resume -> {
                    add("--resume")
                    add(session.id.toString())
                }
            }
            request.effort?.let {
                add("--effort")
                add(cliEffort(it))
            }
            request.maxTurns?.let {
                add("--max-turns")
                add(it.toString())
            }
        }

    /**
     * The complete environment of the CLI: an allow-list, never the service's own environment, which
     * carries `AGENTOS_ENCRYPTION_KEY`, provider API keys and database credentials. Leaving out every
     * `ANTHROPIC_*` and `CLAUDE_CODE_*` variable also keeps an API key, a long-lived OAuth token or a
     * Bedrock/Vertex switch from taking precedence over the user's subscription login.
     */
    fun environment(
        userId: String,
        inherited: Map<String, String>,
    ): Map<String, String> =
        buildMap {
            put("PATH", inherited["PATH"] ?: DEFAULT_PATH)
            put("HOME", inherited["HOME"] ?: System.getProperty("user.home"))
            // The macOS keychain, where the CLI keeps the login, is not readable without them.
            val user = inherited["USER"] ?: inherited["LOGNAME"] ?: System.getProperty("user.name")
            put("USER", user)
            put("LOGNAME", inherited["LOGNAME"] ?: user)
            put("LANG", inherited["LANG"] ?: DEFAULT_LANG)
            PASSED_THROUGH.forEach { name -> inherited[name]?.let { put(name, it) } }
            put("CLAUDE_CONFIG_DIR", configDirectory(userId).toString())
            // The Git snapshot of the working directory changes the prompt prefix and breaks the cache on resume.
            put("CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS", "1")
            // The user's interactive CLI updates the shared binary; a headless turn must not swap it mid-run.
            put("DISABLE_AUTOUPDATER", "1")
        }

    fun mcpConfiguration(server: ClaudeCodeMcpServer): String =
        MAPPER.writeValueAsString(
            mapOf(
                "mcpServers" to
                    mapOf(
                        MCP_SERVER_NAME to
                            mapOf(
                                "type" to "http",
                                "url" to server.url,
                                "headers" to mapOf("Authorization" to "Bearer ${server.bearerToken}"),
                                // Every AgentOS tool in the prompt, never deferred behind a tool search.
                                "alwaysLoad" to true,
                                "timeout" to server.toolTimeout.toMillis(),
                            ),
                    ),
            ),
        )

    companion object {
        /** Name of the AgentOS server in the MCP configuration; the CLI names its tools `mcp__agentos__<tool>`. */
        const val MCP_SERVER_NAME = "agentos"
        const val MCP_TOOL_PREFIX = "mcp__${MCP_SERVER_NAME}__"
        const val WORKING_DIRECTORIES = "workdirs"

        private const val DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin"
        private const val DEFAULT_LANG = "C.UTF-8"
        private val MAPPER = jacksonObjectMapper()
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9_-]{1,128}")

        /** Network settings the CLI needs to reach Anthropic from a corporate network. */
        private val PASSED_THROUGH =
            listOf(
                "TMPDIR",
                "HTTPS_PROXY",
                "https_proxy",
                "HTTP_PROXY",
                "http_proxy",
                "NO_PROXY",
                "no_proxy",
                "NODE_EXTRA_CA_CERTS",
            )

        /**
         * Settings passed on top of `--setting-sources ""`. The built-in plugins that read `AGENTS.md`
         * files or teach plugin authoring are turned off; telemetry and privacy plugins keep the user's
         * own configuration.
         */
        internal val SETTINGS_JSON: String =
            MAPPER.writeValueAsString(
                mapOf(
                    "disableAllHooks" to true,
                    "enabledPlugins" to
                        mapOf(
                            "cc-plugin-agents-md@builtin" to false,
                            "cc-plugin-plugin-authoring@builtin" to false,
                        ),
                    "autoMemoryEnabled" to false,
                    "claudeMdExcludes" to listOf("**/CLAUDE.md", "**/CLAUDE.local.md", "**/.claude/rules/**", "**/AGENTS.md"),
                ),
            )

        /** The CLI has no level without reasoning: [ReasoningEffort.NONE] asks for the lowest one. */
        fun cliEffort(effort: ReasoningEffort): String =
            when (effort) {
                ReasoningEffort.NONE, ReasoningEffort.LOW -> "low"
                ReasoningEffort.MEDIUM -> "medium"
                ReasoningEffort.HIGH -> "high"
                ReasoningEffort.XHIGH -> "xhigh"
                ReasoningEffort.MAX -> "max"
            }

        private fun requireSafeSegment(
            value: String,
            label: String,
        ): String {
            require(SAFE_SEGMENT.matches(value)) { "Unexpected $label for a Claude Code directory: $value" }
            return value
        }

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
