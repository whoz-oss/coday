package io.whozoss.agentos.claudeCode

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

/**
 * Configuration of the Claude Code runtime: AgentOS drives the official `claude` CLI, signed in by the
 * user with their own Claude subscription, instead of calling a model API with a key.
 *
 * AgentOS never reads, stores or forwards the user's Claude credentials: the CLI keeps them in its own
 * configuration directory (or the macOS keychain) and the user signs in from a terminal with
 * `CLAUDE_CONFIG_DIR=<config-root>/<userId> claude auth login`. See `agentos/docs/claude-code-runtime.md`.
 *
 * | Property | Env var | Default | Purpose |
 * | --- | --- | --- | --- |
 * | `enabled` | `AGENTOS_CLAUDE_CODE_ENABLED` | `false` | Allows Claude Code providers on this instance |
 * | `executable` | `AGENTOS_CLAUDE_CODE_EXECUTABLE` | `claude` | Absolute path or PATH-resolved name of the CLI |
 * | `configRoot` | `AGENTOS_CLAUDE_CODE_CONFIG_ROOT` | `~/.coday/agentos/claude-code` | Parent of the per-user CLI configuration directories |
 * | `minimumVersion` | `AGENTOS_CLAUDE_CODE_MINIMUM_VERSION` | `2.1.317` | Oldest CLI version whose stream protocol was verified |
 * | `statusTimeout` | `AGENTOS_CLAUDE_CODE_STATUS_TIMEOUT` | `10s` | Timeout of `claude --version` and `claude auth status` |
 * | `maxConcurrentProcesses` | `AGENTOS_CLAUDE_CODE_MAX_CONCURRENT_PROCESSES` | `4` | Turns running at once on the instance; others wait |
 * | `idleTimeout` | `AGENTOS_CLAUDE_CODE_IDLE_TIMEOUT` | `10m` | A turn whose CLI stays silent this long is stopped |
 */
@ConfigurationProperties(prefix = "agentos.claude-code")
data class ClaudeCodeProperties(
    /**
     * Off by default. Anthropic only allows a subscription to be used through the unmodified CLI signed
     * in by its owner, so this is meant for an instance a user runs for themselves.
     */
    val enabled: Boolean = false,
    /** The CLI executable. A bare name is resolved through the `PATH` handed to the child process. */
    val executable: String = "claude",
    /** Each user gets `<configRoot>/<userId>` as `CLAUDE_CONFIG_DIR`, never the CLI's default `~/.claude`. */
    val configRoot: Path = Path.of(System.getProperty("user.home"), ".coday", "agentos", "claude-code"),
    /** Older CLIs are refused: their `stream-json` output was not checked against the parser. */
    val minimumVersion: String = "2.1.317",
    val statusTimeout: Duration = Duration.ofSeconds(10),
    /**
     * Several CLI processes sharing one configuration directory race to refresh the subscription token,
     * and each process holds a Node runtime in memory.
     */
    val maxConcurrentProcesses: Int = 4,
    /**
     * The CLI streams while the model writes or thinks, and reports its API retries: a turn that prints
     * nothing for this long is considered stuck and stopped.
     */
    val idleTimeout: Duration = Duration.ofMinutes(10),
)
