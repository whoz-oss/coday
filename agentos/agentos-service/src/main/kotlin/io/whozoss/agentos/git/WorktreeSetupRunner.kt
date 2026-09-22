package io.whozoss.agentos.git

import mu.KLogging
import org.springframework.stereotype.Component
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
 * See the residual-risk section of `agentos/docs/git-workspaces.md`.
 */
@Component
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
    ) {
        val command = settings.setupCommand?.takeIf { it.isNotBlank() } ?: return

        logger.info { "Running setup for namespace ${settings.namespaceId} in $worktreePath" }

        val process =
            ProcessBuilder("/bin/sh", "-c", "trap 'wait' EXIT\n$command")
                .directory(worktreePath.toFile())
                .redirectErrorStream(false)
                .also { builder ->
                    val env = builder.environment()
                    env.clear()
                    env["PATH"] = System.getenv("PATH") ?: DEFAULT_PATH
                    env["HOME"] = worktreePath.toString()
                    env["LANG"] = "C"
                    env["LC_ALL"] = "C"
                    env["CI"] = "true"
                }.start()

        val result =
            try {
                BoundedProcessOutput.await(process, properties.setupTimeout, properties.maxOutputChars)
            } catch (e: TimeoutException) {
                throw IllegalStateException("Setup command timed out after ${properties.setupTimeout}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Setup command was interrupted", e)
            }

        if (result.exitCode != 0) {
            val output = (result.stdout + result.stderr).take(properties.maxOutputChars)
            throw IllegalStateException("Setup command failed (exit ${result.exitCode}): ${output.trim().takeLast(MAX_REPORTED_OUTPUT)}")
        }
        logger.info { "Setup completed for namespace ${settings.namespaceId}" }
    }

    companion object : KLogging() {
        private const val DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin"
        private const val MAX_REPORTED_OUTPUT = 2_000
    }
}
