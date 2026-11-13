package io.whozoss.agentos.git

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import io.whozoss.agentos.git.core.BoundedProcessOutput
import io.whozoss.agentos.git.core.GitExecutionProperties
import mu.KLogging
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException

/**
 * Runs the namespace's configured setup command inside a freshly created worktree.
 *
 * ## This executes code the branch controls
 *
 * The command runs server-side, as the service user, before any agent has touched the case. A
 * typical value is a dependency install, and a dependency install executes lifecycle scripts from
 * the branch's own manifest. Creating a root case only needs namespace `MEMBER`, so anyone who can
 * push a branch can run code here.
 *
 * Two things follow, and neither is optional:
 *
 * 1. The child process gets a **cleared environment** plus a minimal allow-list, so
 *    `AGENTOS_ENCRYPTION_KEY`, provider API keys and database credentials are not readable from it.
 * 2. Operators should prefer a command that disables lifecycle scripts (`npm ci --ignore-scripts`,
 *    `pnpm install --ignore-scripts`). This runner cannot enforce that without breaking setups
 *    that legitimately need them, so it is a documented deployment choice, not a guarantee.
 *
 * When the command fails, the server log (not the exception, nor the persisted public failure
 * reason) receives the command, the exit code and the redacted stderr and stdout, so that an
 * administrator can diagnose the failure. Per stream, the last `maxOutputChars` characters are
 * kept, so the final error is never the part that is lost.
 *
 * See the Deployment section of `agentos/docs/git-workspaces.md`.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class WorktreeSetupRunner(
    private val properties: GitExecutionProperties,
) {
    /**
     * Execute [GitRepositorySettings.setupCommand] in [worktreePath]. Does nothing when no setup
     * command is configured.
     *
     * @throws IllegalStateException when the command fails or times out — preparation must not
     *   report a workspace as ready when its setup did not complete.
     */
    fun run(
        settings: GitRepositorySettings,
        worktreePath: Path,
        setupHome: Path,
    ) {
        val command = settings.setupCommand?.takeIf { it.isNotBlank() } ?: return

        logger.info { "Running setup for namespace ${settings.namespaceId} in $worktreePath" }

        // Setup state is workspace-owned but must not be exposed as shared Exchange documents.
        val home = Files.createDirectories(setupHome.toAbsolutePath().normalize())
        val cache = Files.createDirectories(home.resolve(".cache"))
        val context = "namespace ${settings.namespaceId}, worktree $worktreePath"
        val loggedCommand = redactSecrets(command)

        val process =
            try {
                ProcessBuilder("/bin/sh", "-c", "trap 'wait' EXIT\n$command")
                    .directory(worktreePath.toFile())
                    .redirectErrorStream(false)
                    .also { builder ->
                        val env = builder.environment()
                        env.clear()
                        env["PATH"] = System.getenv("PATH") ?: DEFAULT_PATH
                        env["HOME"] = home.toString()
                        env["XDG_CACHE_HOME"] = cache.toString()
                        // pnpm derives its default store from HOME and may fall back to a location outside the
                        // workspace support directory: pin it inside, so it is removed with the workspace.
                        env["npm_config_store_dir"] = home.resolve(PNPM_STORE_DIRECTORY).toString()
                        env["LANG"] = "C"
                        env["LC_ALL"] = "C"
                        env["CI"] = "true"
                    }.start()
            } catch (e: IOException) {
                logger.error(e) { "Setup command could not start ($context), command: $loggedCommand" }
                throw IllegalStateException("Setup command could not start", e)
            }

        val result =
            try {
                BoundedProcessOutput.await(
                    process,
                    properties.setupTimeout,
                    properties.maxOutputChars,
                    BoundedProcessOutput.Retain.TAIL,
                )
            } catch (e: TimeoutException) {
                logger.error {
                    "Setup command timed out after ${properties.setupTimeout} ($context), command: $loggedCommand"
                }
                throw IllegalStateException("Setup command timed out after ${properties.setupTimeout}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.error {
                    "Setup command was interrupted ($context, timeout ${properties.setupTimeout}), command: $loggedCommand"
                }
                throw IllegalStateException("Setup command was interrupted", e)
            }

        if (result.exitCode != 0) {
            logger.error {
                buildString {
                    append("Setup command failed ($context): exit ${result.exitCode}, ")
                    append("output truncated: ${result.truncated}, command: $loggedCommand")
                    appendOutput("stderr", result.stderr, result.truncated && result.stderr.length >= properties.maxOutputChars)
                    appendOutput("stdout", result.stdout, result.truncated && result.stdout.length >= properties.maxOutputChars)
                }
            }
            // The output goes to the server log above (redacted; the environment is cleared, so
            // service secrets are not present in it) but stays out of the exception message and out of
            // the persisted public failure reason, which callers expose to users.
            throw IllegalStateException("Setup command failed (exit ${result.exitCode})")
        }
        logger.info { "Setup completed for namespace ${settings.namespaceId}" }
    }

    companion object : KLogging() {
        private const val DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin"

        /** Name, under the setup home, of the directory holding the pnpm content-addressable store. */
        const val PNPM_STORE_DIRECTORY = ".pnpm-store"

        private const val MASK = "***"

        /** Pattern and replacement pairs, applied in order. */
        private val SECRET_PATTERNS: List<Pair<Regex, String>> =
            listOf(
                // scheme://user:pass@host -> scheme://***@host
                Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)[^/\s@]+@""") to "\$1$MASK@",
                // Authorization: Bearer xxx / Basic xxx
                Regex("""(Authorization:\s*(?:Bearer|Basic)\s+)\S+""", RegexOption.IGNORE_CASE) to "\$1$MASK",
                // Well-known token shapes: GitHub, npm, GitLab
                Regex("""gh[pousr]_[A-Za-z0-9]{20,}""") to MASK,
                Regex("""github_pat_[A-Za-z0-9_]{20,}""") to MASK,
                Regex("""npm_[A-Za-z0-9]{20,}""") to MASK,
                Regex("""glpat-[A-Za-z0-9_\-]{20,}""") to MASK,
                // key=value pairs: keep the key, mask the value
                Regex(
                    """((?:_authToken|_auth|password|token)\s*=\s*)(?:"[^"]*"|'[^']*'|[^\s'"&]+)""",
                    RegexOption.IGNORE_CASE,
                ) to "\$1$MASK",
            )

        /** Masks common credential shapes in text that is about to be logged. Best effort, not a guarantee. */
        internal fun redactSecrets(text: String): String =
            SECRET_PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

        /** Appends the labelled, redacted [output]; nothing when it is blank. */
        private fun StringBuilder.appendOutput(
            label: String,
            output: String,
            truncated: Boolean,
        ) {
            val redacted = redactSecrets(output).trimEnd()
            if (redacted.isBlank()) return
            val note = if (truncated) " (truncated: beginning dropped, last ${output.length} chars kept)" else ""
            append("\n--- $label$note ---\n")
            append(redacted)
        }
    }
}
